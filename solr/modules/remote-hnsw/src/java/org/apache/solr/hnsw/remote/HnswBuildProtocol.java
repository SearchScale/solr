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

/** HTTP paths and headers for the v1 HNSW build service. */
public final class HnswBuildProtocol {
  public static final String HEALTH_PATH = "/v1/health";
  public static final String BUILD_PATH = "/v1/hnsw/build";

  public static final String HDR_DIMENSION = "X-RHNSW-Dimension";
  public static final String HDR_COUNT = "X-RHNSW-Count";
  public static final String HDR_SIMILARITY = "X-RHNSW-Similarity";
  public static final String HDR_MAX_CONN = "X-RHNSW-MaxConn";
  public static final String HDR_BEAM_WIDTH = "X-RHNSW-BeamWidth";
  public static final String HDR_WRITER_THREADS = "X-RHNSW-Cuvs-WriterThreads";
  public static final String HDR_INT_GRAPH_DEGREE = "X-RHNSW-Cuvs-IntGraphDegree";
  public static final String HDR_GRAPH_DEGREE = "X-RHNSW-Cuvs-GraphDegree";
  public static final String HDR_HNSW_LAYERS = "X-RHNSW-Cuvs-HnswLayers";
  public static final String HDR_CAGRA_ALGO = "X-RHNSW-Cuvs-CagraGraphBuildAlgo";
  public static final String HDR_BUILDER = "X-RHNSW-Builder";
  public static final String HDR_BUILDER_USED = "X-RHNSW-Builder-Used";

  public static final String CONTENT_TYPE_VECTORS = "application/octet-stream";
  public static final String CONTENT_TYPE_GRAPH = "application/x-rhNSW-graph";

  private HnswBuildProtocol() {}
}
