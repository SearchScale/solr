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

import static org.apache.solr.hnsw.remote.HnswBuildProtocol.BUILD_PATH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_VECTORS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BEAM_WIDTH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BUILDER;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BUILDER_USED;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_CAGRA_ALGO;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_COUNT;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_DIMENSION;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_HNSW_LAYERS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_INT_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_MAX_CONN;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_SIMILARITY;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_WRITER_THREADS;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import org.apache.lucene.util.SuppressForbidden;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.util.EnvUtils;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Hits a live service once with {@code gpu} and once with {@code cpu}. Skipped unless {@code
 * SOLR_HNSW_BUILD_SERVICE_URL} is set.
 *
 * <pre>{@code
 * SOLR_HNSW_BUILD_SERVICE_URL=http://host:18765 ./gradlew \
 *   :solr:modules:remote-hnsw:test --tests ManualBuilderComparisonTest \
 *   -Ptests.useSecurityManager=false
 * }</pre>
 */
@SuppressForbidden(reason = "Manual diagnostic test prints wire-level details to stdout")
public class ManualBuilderComparisonTest extends SolrTestCase {

  private static String serviceUrl;

  @BeforeClass
  public static void checkServiceUrl() {
    serviceUrl = EnvUtils.getProperty(RemoteHnswCodecFactory.SERVICE_URL_PROP);
    Assume.assumeTrue(
        "Set -Dsolr.hnsw.build.service.url=http://host:port (or SOLR_HNSW_BUILD_SERVICE_URL) to"
            + " run this manual test against a real HNSW build service",
        serviceUrl != null && !serviceUrl.isBlank());
  }

  @Test
  public void compareGpuAndCpuBuilders() throws Exception {
    int n = 60;
    int dim = 16;
    List<float[]> vectors = HnswGraphCodecTest.randomVectors(n, dim, 7);
    HnswBuildParams gpuParams =
        new HnswBuildParams(
            dim, n, "EUCLIDEAN", 16, 100, 4, 128, 64, 1, "NN_DESCENT", HnswBuilder.GPU);
    HnswBuildParams cpuParams =
        new HnswBuildParams(
            dim, n, "EUCLIDEAN", 16, 100, 4, 128, 64, 1, "NN_DESCENT", HnswBuilder.CPU);

    Result gpu = send(gpuParams, vectors, dim);
    Result cpu = send(cpuParams, vectors, dim);

    System.out.println("=== Remote HNSW build service comparison (" + serviceUrl + ") ===");
    System.out.println("Request: " + n + " vectors x " + dim + " dims (float32 LE)");
    System.out.println("  bytes sent (raw vector payload): " + gpu.bytesSent);
    System.out.println();
    System.out.println("[GPU]  HTTP " + gpu.status + "  builder-used=" + gpu.builderUsed);
    System.out.println("  bytes received (encoded graph): " + gpu.bytesReceived);
    System.out.println(
        "  graph: size="
            + gpu.graph.size()
            + " levels="
            + gpu.graph.numLevels()
            + " entryNode="
            + gpu.graph.entryNode()
            + " maxConn="
            + gpu.graph.maxConn());
    System.out.println();
    System.out.println("[CPU]  HTTP " + cpu.status + "  builder-used=" + cpu.builderUsed);
    System.out.println("  bytes received (encoded graph): " + cpu.bytesReceived);
    System.out.println(
        "  graph: size="
            + cpu.graph.size()
            + " levels="
            + cpu.graph.numLevels()
            + " entryNode="
            + cpu.graph.entryNode()
            + " maxConn="
            + cpu.graph.maxConn());

    assertEquals(200, gpu.status);
    assertEquals(200, cpu.status);
    assertEquals("gpu", gpu.builderUsed);
    assertEquals("cpu", cpu.builderUsed);
    assertEquals(n, gpu.graph.size());
    assertEquals(n, cpu.graph.size());
  }

  private static Result send(HnswBuildParams params, List<float[]> vectors, int dim)
      throws Exception {
    byte[] body = HnswGraphCodec.encodeVectors(vectors, dim);
    URI uri = URI.create(trimSlash(serviceUrl) + BUILD_PATH);
    HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
    try {
      conn.setConnectTimeout(60_000);
      conn.setReadTimeout(600_000);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", CONTENT_TYPE_VECTORS);
      conn.setRequestProperty(HDR_DIMENSION, Integer.toString(params.dimension()));
      conn.setRequestProperty(HDR_COUNT, Integer.toString(params.count()));
      conn.setRequestProperty(HDR_SIMILARITY, params.similarityFunction());
      conn.setRequestProperty(HDR_MAX_CONN, Integer.toString(params.hnswM()));
      conn.setRequestProperty(HDR_BEAM_WIDTH, Integer.toString(params.hnswEfConstruction()));
      conn.setRequestProperty(HDR_WRITER_THREADS, Integer.toString(params.cuvsWriterThreads()));
      conn.setRequestProperty(HDR_INT_GRAPH_DEGREE, Integer.toString(params.cuvsIntGraphDegree()));
      conn.setRequestProperty(HDR_GRAPH_DEGREE, Integer.toString(params.cuvsGraphDegree()));
      conn.setRequestProperty(HDR_HNSW_LAYERS, Integer.toString(params.cuvsHnswLayers()));
      conn.setRequestProperty(HDR_CAGRA_ALGO, params.cuvsCagraGraphBuildAlgo());
      conn.setRequestProperty(HDR_BUILDER, params.builder().wireName());
      conn.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body);
      }
      int status = conn.getResponseCode();
      byte[] response = conn.getInputStream().readAllBytes();
      String builderUsed = conn.getHeaderField(HDR_BUILDER_USED);
      HnswNeighborGraph graph = HnswGraphCodec.decode(response);
      return new Result(status, builderUsed, body.length, response.length, graph);
    } finally {
      conn.disconnect();
    }
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private record Result(
      int status, String builderUsed, int bytesSent, int bytesReceived, HnswNeighborGraph graph) {}
}
