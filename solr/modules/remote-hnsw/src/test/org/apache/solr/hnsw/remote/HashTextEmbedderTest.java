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
import org.apache.solr.SolrTestCase;
import org.junit.Test;

public class HashTextEmbedderTest extends SolrTestCase {

  @Test
  public void testDeterministic() {
    HashTextEmbedder embedder = new HashTextEmbedder(64);
    float[] a = embedder.embedBatch(List.of("the quick brown fox"))[0];
    float[] b = embedder.embedBatch(List.of("the quick brown fox"))[0];
    assertArrayEquals(a, b, 0.0f);
  }

  @Test
  public void testDifferentTextsDifferentVectors() {
    HashTextEmbedder embedder = new HashTextEmbedder(64);
    float[][] vectors =
        embedder.embedBatch(List.of("the quick brown fox", "completely unrelated content"));
    assertFalse(java.util.Arrays.equals(vectors[0], vectors[1]));
  }

  @Test
  public void testSimilarTextsAreCloserThanUnrelated() {
    HashTextEmbedder embedder = new HashTextEmbedder(256);
    float[][] vectors =
        embedder.embedBatch(
            List.of(
                "the quick brown fox jumps over the lazy dog",
                "a quick brown fox jumps over a lazy dog",
                "completely different subject matter entirely unrelated"));
    double simRelated = dot(vectors[0], vectors[1]);
    double simUnrelated = dot(vectors[0], vectors[2]);
    assertTrue(
        "expected shared-vocabulary texts to score higher cosine similarity ("
            + simRelated
            + ") than unrelated text ("
            + simUnrelated
            + ")",
        simRelated > simUnrelated);
  }

  @Test
  public void testEmptyTextYieldsZeroVector() {
    HashTextEmbedder embedder = new HashTextEmbedder(32);
    float[] vector = embedder.embedBatch(List.of(""))[0];
    for (float v : vector) {
      assertEquals(0.0f, v, 0.0f);
    }
  }

  @Test
  public void testDimensionMustBePositive() {
    expectThrows(IllegalArgumentException.class, () -> new HashTextEmbedder(0));
  }

  private static double dot(float[] a, float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      sum += (double) a[i] * b[i];
    }
    return sum;
  }
}
