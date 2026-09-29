/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.hnsw.remote.service;

import static org.apache.solr.hnsw.remote.EmbedProtocol.EMBED_PATH;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_COUNT;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_DIMENSION;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_MODEL;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.BUILD_PATH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_GRAPH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_VECTORS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BEAM_WIDTH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BUILDER;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BUILDER_USED;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_CAGRA_ALGO;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_COUNT;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_DIMENSION;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_HNSW_LAYERS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_INT_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_MAX_CONN;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_SIMILARITY;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_WRITER_THREADS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HEALTH_PATH;

import com.nvidia.cuvs.lucene.ThreadLocalCuVSResourcesProvider;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.hnsw.remote.EmbedItemCodec;
import org.apache.solr.hnsw.remote.EmbedderRegistry;
import org.apache.solr.hnsw.remote.HnswBuildParams;
import org.apache.solr.hnsw.remote.HnswBuilder;
import org.apache.solr.hnsw.remote.HnswGraphCodec;
import org.apache.solr.hnsw.remote.HnswNeighborGraph;
import org.apache.solr.hnsw.remote.LuceneCpuHnswBuilder;
import org.apache.solr.hnsw.remote.TextEmbedder;

/**
 * Standalone HNSW build / embed service. Run on a CUDA host for CAGRA; {@code --builder cpu} works
 * without a GPU.
 */
@SuppressForbidden(reason = "Standalone process uses JDK HttpServer; System.out is the process log")
public final class HnswBuildServer {
  private static final int DEFAULT_PORT = 18765;
  private static final int DEFAULT_EMBED_DIMENSION = 384;
  static volatile HnswBuilder defaultBuilder = HnswBuilder.AUTO;
  static volatile EmbedderRegistry embedderRegistry;

  public static void main(String[] args) throws Exception {
    int port = DEFAULT_PORT;
    String bind = "0.0.0.0";
    int embedDimension = DEFAULT_EMBED_DIMENSION;
    Path modelsDir = null;
    for (int i = 0; i < args.length; i++) {
      if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      } else if ("--bind".equals(args[i]) && i + 1 < args.length) {
        bind = args[++i];
      } else if ("--builder".equals(args[i]) && i + 1 < args.length) {
        defaultBuilder = HnswBuilder.parse(args[++i]);
      } else if ("--embed-dim".equals(args[i]) && i + 1 < args.length) {
        embedDimension = Integer.parseInt(args[++i]);
      } else if ("--models-dir".equals(args[i]) && i + 1 < args.length) {
        modelsDir = Path.of(args[++i]);
      }
    }
    // hash-demo is always present so the protocol can be exercised without ONNX weights.
    // Real sentence-transformers (minilm, mpnet, ...) register from --models-dir.
    embedderRegistry = EmbedderRegistry.withHashDemoModel(embedDimension);
    int onnxCount = 0;
    if (modelsDir != null) {
      onnxCount = OnnxModelDirectory.registerAll(embedderRegistry, modelsDir);
    }

    HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
    server.createContext(HEALTH_PATH, HnswBuildServer::health);
    server.createContext(BUILD_PATH, HnswBuildServer::build);
    server.createContext(EMBED_PATH, HnswBuildServer::embed);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.out.println("Remote HNSW build service listening on http://" + bind + ":" + port);
    System.out.println("Default builder: " + defaultBuilder.wireName());
    System.out.println("GPU/cuVS supported: " + gpuAvailable());
    System.out.println(
        "Embedding models: "
            + embedderRegistry.modelIds()
            + " (onnx dirs loaded: "
            + onnxCount
            + ")");
  }

  static void health(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      send(exchange, 405, "text/plain", "GET only");
      return;
    }
    boolean gpu = gpuAvailable();
    StringBuilder models = new StringBuilder();
    for (String id : embedderRegistry.modelIds()) {
      if (models.length() > 0) {
        models.append(',');
      }
      models.append('"').append(id).append('"');
    }
    String json =
        "{\"status\":\"ok\",\"gpu\":"
            + gpu
            + ",\"builder\":\""
            + defaultBuilder.wireName()
            + "\",\"models\":["
            + models
            + "]}";
    send(exchange, 200, "application/json", json);
  }

  static void build(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      send(exchange, 405, "text/plain", "POST only");
      return;
    }
    try {
      HnswBuildParams params = readParams(exchange.getRequestHeaders());
      long t0 = System.nanoTime();
      float[][] vectors;
      try (InputStream in = exchange.getRequestBody()) {
        vectors = HnswGraphCodec.readVectors(in, params.count(), params.dimension());
      }
      long decodeMs = (System.nanoTime() - t0) / 1_000_000L;
      HnswBuilder used = params.builder().resolve(defaultBuilder, gpuAvailable());
      long t1 = System.nanoTime();
      HnswNeighborGraph graph = buildGraph(vectors, params, used);
      long buildMs = (System.nanoTime() - t1) / 1_000_000L;
      System.out.println(
          "POST /v1/hnsw/build n="
              + params.count()
              + " dim="
              + params.dimension()
              + " builder="
              + used.wireName()
              + " decode="
              + decodeMs
              + "ms build="
              + buildMs
              + "ms");
      exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_GRAPH);
      exchange.getResponseHeaders().set(HDR_BUILDER_USED, used.wireName());
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        HnswGraphCodec.encode(out, graph);
      }
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      send(exchange, 500, "text/plain", msg);
    }
  }

  static void embed(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      send(exchange, 405, "text/plain", "POST only");
      return;
    }
    try {
      String modelId = header(exchange.getRequestHeaders(), HDR_EMBED_MODEL);
      int count = headerInt(exchange.getRequestHeaders(), HDR_EMBED_COUNT);
      TextEmbedder embedder = embedderRegistry.resolve(modelId);

      List<String> texts;
      try (InputStream in = exchange.getRequestBody()) {
        texts = EmbedItemCodec.readTextItems(in, count);
      }
      float[][] vectors = embedder.embedBatch(texts);

      exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_VECTORS);
      exchange
          .getResponseHeaders()
          .set(HDR_EMBED_DIMENSION, Integer.toString(embedder.dimension()));
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        HnswGraphCodec.writeVectors(out, Arrays.asList(vectors), embedder.dimension());
      }
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      send(exchange, 500, "text/plain", msg);
    }
  }

  static HnswNeighborGraph buildGraph(float[][] vectors, HnswBuildParams params, HnswBuilder used)
      throws IOException {
    if (used == HnswBuilder.GPU) {
      return CagraHnswGraphBuilder.build(vectors, params);
    }
    VectorSimilarityFunction similarity =
        VectorSimilarityFunction.valueOf(params.similarityFunction());
    return LuceneCpuHnswBuilder.build(
        Arrays.asList(vectors), similarity, params.hnswM(), params.hnswEfConstruction());
  }

  static boolean gpuAvailable() {
    try {
      return ThreadLocalCuVSResourcesProvider.isSupported();
    } catch (Throwable t) {
      return false;
    }
  }

  static HnswBuildParams readParams(Headers headers) {
    return new HnswBuildParams(
        headerInt(headers, HDR_DIMENSION),
        headerInt(headers, HDR_COUNT),
        header(headers, HDR_SIMILARITY).toUpperCase(Locale.ROOT),
        headerInt(headers, HDR_MAX_CONN),
        headerInt(headers, HDR_BEAM_WIDTH),
        headerInt(headers, HDR_WRITER_THREADS, 32),
        headerInt(headers, HDR_INT_GRAPH_DEGREE, 128),
        headerInt(headers, HDR_GRAPH_DEGREE, 64),
        headerInt(headers, HDR_HNSW_LAYERS, 1),
        header(headers, HDR_CAGRA_ALGO, "NN_DESCENT").toUpperCase(Locale.ROOT),
        HnswBuilder.parse(header(headers, HDR_BUILDER, HnswBuilder.AUTO.wireName())));
  }

  private static String header(Headers headers, String name) {
    String v = headers.getFirst(name);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("Missing header " + name);
    }
    return v;
  }

  private static String header(Headers headers, String name, String dflt) {
    String v = headers.getFirst(name);
    return v == null || v.isBlank() ? dflt : v;
  }

  private static int headerInt(Headers headers, String name) {
    return Integer.parseInt(header(headers, name));
  }

  private static int headerInt(Headers headers, String name, int dflt) {
    String v = headers.getFirst(name);
    return v == null || v.isBlank() ? dflt : Integer.parseInt(v);
  }

  private static void send(HttpExchange exchange, int code, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
