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
import java.util.Map;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.EnvUtils;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Hits a live {@code POST /v1/embed} for each registered model id plus {@code hash-demo}. Skipped
 * unless {@code SOLR_HNSW_BUILD_SERVICE_URL} is set.
 */
public class ManualOnnxEmbedClientSmokeTest extends SolrTestCase {
  private static final Map<String, Integer> EXPECTED_DIMS =
      Map.of(
          "hash-demo", 384,
          "minilm", 384,
          "minilm-l12", 384,
          "mpnet", 768,
          "multilingual-minilm", 384,
          "multilingual-mpnet", 768);

  private static RemoteEmbeddingClient client;

  @BeforeClass
  public static void requireLiveService() {
    String serviceUrl = EnvUtils.getProperty(RemoteHnswCodecFactory.SERVICE_URL_PROP);
    Assume.assumeTrue(
        "Set SOLR_HNSW_BUILD_SERVICE_URL to run ONNX embed checks against a real service",
        serviceUrl != null && !serviceUrl.isBlank());
    client = new RemoteEmbeddingClient(serviceUrl, null, 120);
  }

  @Test
  public void testEveryRegisteredModelReturnsExpectedDimension() throws Exception {
    for (Map.Entry<String, Integer> entry : EXPECTED_DIMS.entrySet()) {
      float[][] vectors = client.embed(entry.getKey(), List.of("the quick brown fox"));
      assertEquals(1, vectors.length);
      assertEquals(
          "dimension for model " + entry.getKey(), (int) entry.getValue(), vectors[0].length);
    }
  }

  @Test
  public void testMinilmIsDeterministicAndSemantic() throws Exception {
    String relatedA = "the quick brown fox jumps over the lazy dog";
    String relatedB = "a fast brown fox leaps over a sleepy dog";
    String unrelated = "completely unrelated content about tax law";
    float[][] first = client.embed("minilm", List.of(relatedA, relatedB, unrelated));
    float[][] second = client.embed("minilm", List.of(relatedA));
    assertArrayEquals(first[0], second[0], 0.0f);
    assertTrue(
        "related MiniLM texts should outrank unrelated",
        dot(first[0], first[1]) > dot(first[0], first[2]));
  }

  @Test
  public void testUnknownModelFails() {
    SolrException ex =
        expectThrows(SolrException.class, () -> client.embed("no-such-model", List.of("text")));
    assertEquals(500, ex.code());
  }

  private static double dot(float[] a, float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      sum += (double) a[i] * b[i];
    }
    return sum;
  }
}
