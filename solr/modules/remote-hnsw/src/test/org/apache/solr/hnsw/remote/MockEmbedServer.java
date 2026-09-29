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

import static org.apache.solr.hnsw.remote.EmbedProtocol.EMBED_PATH;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_COUNT;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_DIMENSION;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_MODEL;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_VECTORS;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.util.SuppressForbidden;

/**
 * In-process mock for {@code POST /v1/embed}, backed by the deterministic hash-demo embedder - for
 * testing the {@code RemoteEmbeddingUpdateProcessor} without a real model. Counts requests so tests
 * can assert batching actually happened (one call per flush, not one per document).
 */
@SuppressForbidden(reason = "JDK HttpServer is a self-contained mock for the embed protocol")
final class MockEmbedServer implements AutoCloseable {
  private final HttpServer server;
  private final EmbedderRegistry registry;
  private final AtomicInteger requestCount = new AtomicInteger();

  MockEmbedServer(int dimension) throws IOException {
    this.registry = EmbedderRegistry.withHashDemoModel(dimension);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(EMBED_PATH, this::embed);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  int requestCount() {
    return requestCount.get();
  }

  private void embed(HttpExchange exchange) throws IOException {
    requestCount.incrementAndGet();
    try {
      String modelId = exchange.getRequestHeaders().getFirst(HDR_EMBED_MODEL);
      int count = Integer.parseInt(exchange.getRequestHeaders().getFirst(HDR_EMBED_COUNT));
      TextEmbedder embedder = registry.resolve(modelId);

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
