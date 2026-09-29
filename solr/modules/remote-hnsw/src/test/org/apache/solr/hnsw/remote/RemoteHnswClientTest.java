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

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.SolrException;
import org.junit.Test;

@SuppressForbidden(reason = "JDK HttpServer is a self-contained mock for HTTP error handling")
public class RemoteHnswClientTest extends SolrTestCase {

  @Test
  public void testBuildAgainstMockServer() throws Exception {
    try (MockHnswBuildServer server = new MockHnswBuildServer()) {
      RemoteHnswClient client = new RemoteHnswClient(server.url(), null, 30);
      List<float[]> vectors = HnswGraphCodecTest.randomVectors(16, 4, 11);
      HnswBuildParams params =
          new HnswBuildParams(4, vectors.size(), "EUCLIDEAN", 8, 50, 4, 32, 16, 1, "NN_DESCENT");
      HnswNeighborGraph graph = client.build(vectors, params);
      assertEquals(vectors.size(), graph.size());
      assertTrue(graph.numLevels() >= 1);
    }
  }

  @Test
  public void testHttpErrorFails() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        BUILD_PATH,
        exchange -> {
          byte[] msg = "boom".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, msg.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(msg);
          }
        });
    server.start();
    try {
      RemoteHnswClient client =
          new RemoteHnswClient("http://127.0.0.1:" + server.getAddress().getPort(), null, 10);
      List<float[]> vectors = HnswGraphCodecTest.randomVectors(4, 4, 3);
      HnswBuildParams params =
          new HnswBuildParams(
              4, 4, VectorSimilarityFunction.EUCLIDEAN.name(), 8, 50, 1, 8, 8, 1, "NN_DESCENT");
      SolrException ex = expectThrows(SolrException.class, () -> client.build(vectors, params));
      assertEquals(500, ex.code());
    } finally {
      server.stop(0);
    }
  }
}
