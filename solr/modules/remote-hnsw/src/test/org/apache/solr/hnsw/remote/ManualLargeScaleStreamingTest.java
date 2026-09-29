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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.RandomAccess;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.util.EnvUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round-trip a vector payload larger than a single Java {@code byte[]} (~2GB). Skipped unless
 * {@code SOLR_REMOTEHNSW_LARGESCALE=true}.
 *
 * <pre>{@code
 * SOLR_REMOTEHNSW_LARGESCALE=true ./gradlew :solr:modules:remote-hnsw:test \
 *   --tests ManualLargeScaleStreamingTest -Ptests.heapsize=3g -Ptests.useSecurityManager=false
 * }</pre>
 */
@SuppressForbidden(reason = "Manual diagnostic test writes a real temp file and prints to stdout")
public class ManualLargeScaleStreamingTest extends SolrTestCase {

  @Test
  public void streamPastOldArrayLimit() throws Exception {
    Assume.assumeTrue(
        "Set SOLR_REMOTEHNSW_LARGESCALE=true (and a bumped -Ptests.heapsize) to run this"
            + " manual large-scale test",
        Boolean.parseBoolean(EnvUtils.getProperty("solr.remotehnsw.largescale", "false")));

    // 750,000 x 768-dim FLOAT32 is ~2.15GB raw -- past the ~699,178-vector ceiling that
    // ByteBuffer.allocate(int) used to hit, and past Java's ~2GB single-array length limit.
    int count = 750_000;
    int dim = 768;
    long payloadBytes = HnswGraphCodec.vectorPayloadBytes(count, dim);
    assertTrue(
        "test is only meaningful if it exceeds the old array-length ceiling",
        payloadBytes > Integer.MAX_VALUE);

    RandomVectorList vectors = new RandomVectorList(count, dim, 123);
    Path tmp = newScratchFile();

    long writeStart = System.nanoTime();
    try (OutputStream out = Files.newOutputStream(tmp)) {
      HnswGraphCodec.writeVectors(out, vectors, dim);
    }
    long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
    assertEquals(payloadBytes, Files.size(tmp));

    long readStart = System.nanoTime();
    float[][] decoded;
    try (InputStream in = Files.newInputStream(tmp)) {
      decoded = HnswGraphCodec.readVectors(in, count, dim);
    }
    long readMs = (System.nanoTime() - readStart) / 1_000_000;

    System.out.println("=== Large-scale streaming codec proof ===");
    System.out.println(
        "vectors="
            + count
            + " dim="
            + dim
            + " payloadBytes="
            + payloadBytes
            + " (> Integer.MAX_VALUE="
            + Integer.MAX_VALUE
            + ")");
    System.out.println("write: " + writeMs + "ms, read: " + readMs + "ms");

    assertEquals(count, decoded.length);
    // Spot-check rather than exhaustively re-diffing 750K arrays: first, last, and a few in
    // between, which is enough to catch any offset/alignment bug in the streaming chunking.
    int[] sampleIndices = {0, 1, count / 4, count / 2, count - 2, count - 1};
    for (int idx : sampleIndices) {
      assertArrayEquals("vector " + idx + " mismatch", vectors.get(idx), decoded[idx], 0.0f);
    }
  }

  /** Deterministic pseudo-random vectors generated on demand, never all held in memory at once. */
  private static final class RandomVectorList extends java.util.AbstractList<float[]>
      implements RandomAccess {
    private final int count;
    private final int dim;
    private final long seed;

    RandomVectorList(int count, int dim, long seed) {
      this.count = count;
      this.dim = dim;
      this.seed = seed;
    }

    @Override
    public float[] get(int index) {
      // Reproducible per-index vector without needing a shared Random or storing all vectors.
      Random random = new Random(seed ^ (index * 0x9E3779B97F4A7C15L));
      float[] v = new float[dim];
      for (int d = 0; d < dim; d++) {
        v[d] = random.nextFloat();
      }
      return v;
    }

    @Override
    public int size() {
      return count;
    }
  }

  private static Path newScratchFile() throws IOException {
    Path dir = createTempDir("remote-hnsw-largescale");
    return Files.createTempFile(dir, "vectors-", ".bin");
  }
}
