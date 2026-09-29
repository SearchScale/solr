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

import java.util.Objects;

/** Build-time parameters sent with a vector batch to the HNSW service. */
public final class HnswBuildParams {
  private final int dimension;
  private final int count;
  private final String similarityFunction;
  private final int hnswM;
  private final int hnswEfConstruction;
  private final int cuvsWriterThreads;
  private final int cuvsIntGraphDegree;
  private final int cuvsGraphDegree;
  private final int cuvsHnswLayers;
  private final String cuvsCagraGraphBuildAlgo;
  private final HnswBuilder builder;

  public HnswBuildParams(
      int dimension,
      int count,
      String similarityFunction,
      int hnswM,
      int hnswEfConstruction,
      int cuvsWriterThreads,
      int cuvsIntGraphDegree,
      int cuvsGraphDegree,
      int cuvsHnswLayers,
      String cuvsCagraGraphBuildAlgo) {
    this(
        dimension,
        count,
        similarityFunction,
        hnswM,
        hnswEfConstruction,
        cuvsWriterThreads,
        cuvsIntGraphDegree,
        cuvsGraphDegree,
        cuvsHnswLayers,
        cuvsCagraGraphBuildAlgo,
        HnswBuilder.AUTO);
  }

  public HnswBuildParams(
      int dimension,
      int count,
      String similarityFunction,
      int hnswM,
      int hnswEfConstruction,
      int cuvsWriterThreads,
      int cuvsIntGraphDegree,
      int cuvsGraphDegree,
      int cuvsHnswLayers,
      String cuvsCagraGraphBuildAlgo,
      HnswBuilder builder) {
    this.dimension = dimension;
    this.count = count;
    this.similarityFunction = Objects.requireNonNull(similarityFunction);
    this.hnswM = hnswM;
    this.hnswEfConstruction = hnswEfConstruction;
    this.cuvsWriterThreads = cuvsWriterThreads;
    this.cuvsIntGraphDegree = cuvsIntGraphDegree;
    this.cuvsGraphDegree = cuvsGraphDegree;
    this.cuvsHnswLayers = cuvsHnswLayers;
    this.cuvsCagraGraphBuildAlgo = cuvsCagraGraphBuildAlgo;
    this.builder = builder == null ? HnswBuilder.AUTO : builder;
  }

  public HnswBuildParams withCount(int newCount) {
    return new HnswBuildParams(
        dimension,
        newCount,
        similarityFunction,
        hnswM,
        hnswEfConstruction,
        cuvsWriterThreads,
        cuvsIntGraphDegree,
        cuvsGraphDegree,
        cuvsHnswLayers,
        cuvsCagraGraphBuildAlgo,
        builder);
  }

  public int dimension() {
    return dimension;
  }

  public int count() {
    return count;
  }

  public String similarityFunction() {
    return similarityFunction;
  }

  public int hnswM() {
    return hnswM;
  }

  public int hnswEfConstruction() {
    return hnswEfConstruction;
  }

  public int cuvsWriterThreads() {
    return cuvsWriterThreads;
  }

  public int cuvsIntGraphDegree() {
    return cuvsIntGraphDegree;
  }

  public int cuvsGraphDegree() {
    return cuvsGraphDegree;
  }

  public int cuvsHnswLayers() {
    return cuvsHnswLayers;
  }

  public String cuvsCagraGraphBuildAlgo() {
    return cuvsCagraGraphBuildAlgo;
  }

  public HnswBuilder builder() {
    return builder;
  }
}
