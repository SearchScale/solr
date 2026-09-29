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

import java.util.List;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.SolrException;
import org.junit.Test;

@SuppressForbidden(reason = "JDK HttpServer is a self-contained mock for the embed protocol")
public class RemoteEmbeddingClientTest extends SolrTestCase {

  @Test
  public void testEmbedAgainstMockServer() throws Exception {
    try (MockEmbedServer server = new MockEmbedServer(32)) {
      RemoteEmbeddingClient client = new RemoteEmbeddingClient(server.url(), null, 30);
      float[][] vectors =
          client.embed("hash-demo", List.of("first document text", "second, unrelated text"));
      assertEquals(2, vectors.length);
      assertEquals(32, vectors[0].length);
      assertFalse(java.util.Arrays.equals(vectors[0], vectors[1]));

      // Same model + same text against a fresh embedder instance must be deterministic.
      float[][] again = client.embed("hash-demo", List.of("first document text"));
      assertArrayEquals(vectors[0], again[0], 0.0f);
    }
  }

  @Test
  public void testUnknownModelFails() throws Exception {
    try (MockEmbedServer server = new MockEmbedServer(16)) {
      RemoteEmbeddingClient client = new RemoteEmbeddingClient(server.url(), null, 10);
      SolrException ex =
          expectThrows(SolrException.class, () -> client.embed("no-such-model", List.of("text")));
      assertEquals(500, ex.code());
    }
  }
}
