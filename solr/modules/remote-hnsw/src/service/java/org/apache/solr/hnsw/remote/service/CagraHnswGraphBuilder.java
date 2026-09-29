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
package org.apache.solr.hnsw.remote.service;

import com.nvidia.cuvs.CagraIndex;
import com.nvidia.cuvs.CagraIndexParams;
import com.nvidia.cuvs.CagraIndexParams.CagraGraphBuildAlgo;
import com.nvidia.cuvs.CagraIndexParams.CuvsDistanceType;
import com.nvidia.cuvs.CuVSDeviceMatrix;
import com.nvidia.cuvs.CuVSIvfPqIndexParams;
import com.nvidia.cuvs.CuVSIvfPqParams;
import com.nvidia.cuvs.CuVSMatrix;
import com.nvidia.cuvs.CuVSResources;
import com.nvidia.cuvs.lucene.GPUBuiltHnswGraph;
import com.nvidia.cuvs.lucene.ThreadLocalCuVSResourcesProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.solr.hnsw.remote.HnswBuildParams;
import org.apache.solr.hnsw.remote.HnswNeighborGraph;

/** Builds an HNSW neighbor list with cuVS CAGRA and returns it as {@link HnswNeighborGraph}. */
public final class CagraHnswGraphBuilder {
  private CagraHnswGraphBuilder() {}

  public static HnswNeighborGraph build(float[][] vectors, HnswBuildParams params)
      throws IOException {
    if (!ThreadLocalCuVSResourcesProvider.isSupported()) {
      throw new IOException("cuVS/GPU is not available on this host");
    }
    if (vectors.length == 0) {
      return HnswNeighborGraph.empty(params.hnswM());
    }
    if (vectors.length == 1) {
      return HnswNeighborGraph.singleNode(params.hnswM());
    }
    VectorSimilarityFunction similarity =
        VectorSimilarityFunction.valueOf(params.similarityFunction());
    CuVSResources resources = ThreadLocalCuVSResourcesProvider.getCuVSResourcesInstance();
    if (resources == null) {
      throw new IOException("cuVS/GPU resources are not available on this host");
    }
    CagraIndexParams indexParams = toCagraIndexParams(params, similarity);
    long t0 = System.nanoTime();
    CagraIndex index = null;
    CuVSDeviceMatrix adjacency = null;
    try {
      index =
          CagraIndex.newBuilder(resources)
              .withDataset(vectors)
              .withIndexParams(indexParams)
              .build();
      adjacency = index.getGraph();
      HnswNeighborGraph graph = toNeighborGraph(vectors.length, params.dimension(), adjacency);
      long ms = (System.nanoTime() - t0) / 1_000_000L;
      System.out.println(
          "CAGRA direct build n="
              + vectors.length
              + " dim="
              + params.dimension()
              + " algo="
              + params.cuvsCagraGraphBuildAlgo()
              + " "
              + ms
              + "ms");
      return graph;
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("CAGRA build failed: " + t.getMessage(), t);
    } finally {
      if (adjacency != null) {
        adjacency.close();
      }
      if (index != null) {
        try {
          index.close();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } catch (Exception ignored) {
          // already returning the graph or a build error
        }
      }
    }
  }

  static HnswNeighborGraph toNeighborGraph(int size, int dimension, CuVSMatrix adjacency)
      throws IOException {
    List<int[]> layerNodes = new ArrayList<>(1);
    layerNodes.add(null);
    List<CuVSMatrix> layerAdjacencies = new ArrayList<>(1);
    layerAdjacencies.add(adjacency);
    HnswGraph graph = new GPUBuiltHnswGraph(size, dimension, layerNodes, layerAdjacencies);
    return HnswNeighborGraph.fromHnswGraph(graph);
  }

  static CagraIndexParams toCagraIndexParams(
      HnswBuildParams params, VectorSimilarityFunction similarity) {
    CagraGraphBuildAlgo algo = CagraGraphBuildAlgo.valueOf(params.cuvsCagraGraphBuildAlgo());
    CuvsDistanceType metric = toCuvsDistance(similarity);
    CagraIndexParams.Builder builder =
        new CagraIndexParams.Builder()
            .withNumWriterThreads(params.cuvsWriterThreads())
            .withIntermediateGraphDegree(params.cuvsIntGraphDegree())
            .withGraphDegree(params.cuvsGraphDegree())
            .withCagraGraphBuildAlgo(algo)
            .withMetric(metric);
    if (algo == CagraGraphBuildAlgo.IVF_PQ) {
      builder.withCuVSIvfPqParams(
          new CuVSIvfPqParams.Builder()
              .withCuVSIvfPqIndexParams(
                  new CuVSIvfPqIndexParams.Builder()
                      .withMetric(metric)
                      .withConservativeMemoryAllocation(true)
                      .build())
              .build());
    }
    return builder.build();
  }

  static CuvsDistanceType toCuvsDistance(VectorSimilarityFunction similarity) {
    return switch (similarity) {
      case EUCLIDEAN -> CuvsDistanceType.L2Expanded;
      case COSINE -> CuvsDistanceType.CosineExpanded;
      case DOT_PRODUCT, MAXIMUM_INNER_PRODUCT -> CuvsDistanceType.InnerProduct;
    };
  }
}
