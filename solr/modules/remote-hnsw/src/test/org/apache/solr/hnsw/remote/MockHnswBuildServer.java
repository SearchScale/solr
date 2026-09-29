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
package org.apache.solr.hnsw.remote;

import static org.apache.solr.hnsw.remote.HnswBuildProtocol.BUILD_PATH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_GRAPH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BEAM_WIDTH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_COUNT;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_DIMENSION;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_MAX_CONN;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_SIMILARITY;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HEALTH_PATH;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.SuppressForbidden;

/** In-process CPU HNSW build server for tests. */
@SuppressForbidden(reason = "JDK HttpServer is a self-contained mock for the HNSW build protocol")
final class MockHnswBuildServer implements AutoCloseable {
  private final HttpServer server;

  MockHnswBuildServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(HEALTH_PATH, this::health);
    server.createContext(BUILD_PATH, this::build);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void health(HttpExchange exchange) throws IOException {
    byte[] body = "{\"status\":\"ok\",\"gpu\":false}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private void build(HttpExchange exchange) throws IOException {
    try {
      int dimension = Integer.parseInt(exchange.getRequestHeaders().getFirst(HDR_DIMENSION));
      int count = Integer.parseInt(exchange.getRequestHeaders().getFirst(HDR_COUNT));
      int maxConn = Integer.parseInt(exchange.getRequestHeaders().getFirst(HDR_MAX_CONN));
      int beamWidth = Integer.parseInt(exchange.getRequestHeaders().getFirst(HDR_BEAM_WIDTH));
      VectorSimilarityFunction similarity =
          VectorSimilarityFunction.valueOf(
              exchange.getRequestHeaders().getFirst(HDR_SIMILARITY).toUpperCase(Locale.ROOT));
      float[][] raw;
      try (InputStream in = exchange.getRequestBody()) {
        raw = HnswGraphCodec.readVectors(in, count, dimension);
      }
      List<float[]> vectors = Arrays.asList(raw);
      HnswNeighborGraph graph = LuceneCpuHnswBuilder.build(vectors, similarity, maxConn, beamWidth);
      exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_GRAPH);
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        HnswGraphCodec.encode(out, graph);
      }
    } catch (Exception e) {
      byte[] msg =
          (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
              .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, msg.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(msg);
      }
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
