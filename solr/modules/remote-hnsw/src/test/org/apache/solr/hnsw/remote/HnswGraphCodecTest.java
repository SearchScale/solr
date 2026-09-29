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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.solr.SolrTestCase;
import org.junit.Test;

public class HnswGraphCodecTest extends SolrTestCase {

  @Test
  public void testEncodeDecodeRoundTrip() throws Exception {
    List<float[]> vectors = randomVectors(32, 8, 7);
    HnswNeighborGraph graph =
        LuceneCpuHnswBuilder.build(vectors, VectorSimilarityFunction.EUCLIDEAN, 8, 50);
    byte[] encoded = HnswGraphCodec.encode(graph);
    HnswNeighborGraph decoded = HnswGraphCodec.decode(encoded);
    assertEquals(graph.size(), decoded.size());
    assertEquals(graph.numLevels(), decoded.numLevels());
    assertEquals(graph.maxConn(), decoded.maxConn());
    assertEquals(graph.entryNode(), decoded.entryNode());
    for (int level = 0; level < graph.numLevels(); level++) {
      assertArrayEquals(graph.nodesOnLevel(level), decoded.nodesOnLevel(level));
      int[] nodes = graph.nodesOnLevel(level);
      for (int i = 0; i < nodes.length; i++) {
        assertArrayEquals(graph.neighbors(level, i), decoded.neighbors(level, i));
      }
    }
  }

  @Test
  public void testEmptyAndSingleNode() throws Exception {
    HnswNeighborGraph empty = HnswNeighborGraph.empty(16);
    HnswNeighborGraph empty2 = HnswGraphCodec.decode(HnswGraphCodec.encode(empty));
    assertEquals(0, empty2.size());
    assertEquals(0, empty2.numLevels());

    HnswNeighborGraph single = HnswNeighborGraph.singleNode(16);
    HnswNeighborGraph single2 = HnswGraphCodec.decode(HnswGraphCodec.encode(single));
    assertEquals(1, single2.size());
    assertEquals(1, single2.numLevels());
    assertEquals(0, single2.neighbors(0, 0).length);
  }

  @Test
  public void testStreamingVectorRoundTrip() throws Exception {
    int count = 50_000;
    int dim = 128;
    List<float[]> vectors = randomVectors(count, dim, 42);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    HnswGraphCodec.writeVectors(bos, vectors, dim);
    byte[] payload = bos.toByteArray();
    assertEquals(HnswGraphCodec.vectorPayloadBytes(count, dim), payload.length);

    float[][] decoded = HnswGraphCodec.readVectors(new ByteArrayInputStream(payload), count, dim);
    assertEquals(count, decoded.length);
    for (int i = 0; i < count; i++) {
      assertArrayEquals(vectors.get(i), decoded[i], 0.0f);
    }
  }

  @Test
  public void testVectorPayloadBytesDoesNotOverflowForLargeBatches() {
    long expected = 10_000_000L * 768L * 4L;
    assertEquals(expected, HnswGraphCodec.vectorPayloadBytes(10_000_000, 768));
    assertTrue(HnswGraphCodec.vectorPayloadBytes(10_000_000, 768) > Integer.MAX_VALUE);
    long maxArrayBytes = Integer.MAX_VALUE;
    long safeCount = maxArrayBytes / (768L * 4L);
    assertTrue(HnswGraphCodec.vectorPayloadBytes((int) safeCount, 768) <= maxArrayBytes);
    assertTrue(HnswGraphCodec.vectorPayloadBytes((int) safeCount + 1, 768) > maxArrayBytes);
  }

  static List<float[]> randomVectors(int count, int dim, long seed) {
    Random random = new Random(seed);
    List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      float[] v = new float[dim];
      for (int d = 0; d < dim; d++) {
        v[d] = random.nextFloat();
      }
      vectors.add(v);
    }
    return vectors;
  }
}
