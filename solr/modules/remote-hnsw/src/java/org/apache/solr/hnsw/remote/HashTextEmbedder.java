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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic hashing embedder for tests. Same text always yields the same vector; no ONNX. */
final class HashTextEmbedder implements TextEmbedder {
  private final int dimension;

  HashTextEmbedder(int dimension) {
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be > 0, got " + dimension);
    }
    this.dimension = dimension;
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public float[][] embedBatch(List<String> texts) {
    float[][] vectors = new float[texts.size()][dimension];
    for (int i = 0; i < texts.size(); i++) {
      vectors[i] = embedOne(texts.get(i));
    }
    return vectors;
  }

  private float[] embedOne(String text) {
    float[] vector = new float[dimension];
    if (text == null || text.isBlank()) {
      return vector;
    }
    for (String token : tokenize(text)) {
      int h = token.hashCode();
      int index = Math.floorMod(h, dimension);
      // A second, independent hash decides the sign, which is the standard "hashing trick" bias
      // mitigation (Weinberger et al.) - without it, unrelated tokens colliding into the same
      // bucket would only ever add up, skewing magnitudes instead of mostly cancelling out.
      int sign = (Integer.rotateLeft(h, 15) & 1) == 0 ? 1 : -1;
      vector[index] += sign;
    }
    normalize(vector);
    return vector;
  }

  private static List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static void normalize(float[] vector) {
    double sumSquares = 0;
    for (float v : vector) {
      sumSquares += (double) v * v;
    }
    if (sumSquares == 0) {
      return;
    }
    float norm = (float) Math.sqrt(sumSquares);
    for (int i = 0; i < vector.length; i++) {
      vector[i] /= norm;
    }
  }
}
