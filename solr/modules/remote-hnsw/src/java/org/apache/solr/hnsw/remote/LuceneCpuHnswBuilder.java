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
import java.util.List;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;

/** CPU HNSW construction used for tests and optional codec fallback. */
public final class LuceneCpuHnswBuilder {
  private LuceneCpuHnswBuilder() {}

  public static HnswNeighborGraph build(
      List<float[]> vectors, VectorSimilarityFunction similarity, int maxConn, int beamWidth)
      throws IOException {
    if (vectors.isEmpty()) {
      return HnswNeighborGraph.empty(maxConn);
    }
    if (vectors.size() == 1) {
      return HnswNeighborGraph.singleNode(maxConn);
    }
    RandomVectorScorerSupplier scorerSupplier =
        FlatVectorScorerUtil.getLucene99FlatVectorsScorer()
            .getRandomVectorScorerSupplier(
                similarity, FloatVectorValues.fromFloats(vectors, vectors.get(0).length));
    HnswGraphBuilder builder =
        HnswGraphBuilder.create(scorerSupplier, maxConn, beamWidth, HnswGraphBuilder.randSeed);
    for (int i = 0; i < vectors.size(); i++) {
      builder.addGraphNode(i);
    }
    return HnswNeighborGraph.fromHnswGraph(builder.getCompletedGraph());
  }
}
