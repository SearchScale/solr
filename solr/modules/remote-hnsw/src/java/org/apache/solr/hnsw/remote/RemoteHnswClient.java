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
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_GRAPH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.CONTENT_TYPE_VECTORS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BEAM_WIDTH;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_BUILDER;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_CAGRA_ALGO;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_COUNT;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_DIMENSION;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_HNSW_LAYERS;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_INT_GRAPH_DEGREE;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_MAX_CONN;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_SIMILARITY;
import static org.apache.solr.hnsw.remote.HnswBuildProtocol.HDR_WRITER_THREADS;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.apache.solr.common.SolrException;

/** HTTP client for {@code POST /v1/hnsw/build}. */
public final class RemoteHnswClient {
  private final String serviceUrl;
  private final String bearerToken;
  private final int timeoutMillis;

  public RemoteHnswClient(String serviceUrl, String bearerToken, int timeoutSeconds) {
    this.serviceUrl = Objects.requireNonNull(serviceUrl, "serviceUrl");
    this.bearerToken = bearerToken;
    this.timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000);
  }

  public HnswNeighborGraph build(List<float[]> vectors, HnswBuildParams params) throws IOException {
    if (vectors.size() != params.count()) {
      throw new IllegalArgumentException(
          "vector count " + vectors.size() + " != params.count " + params.count());
    }
    long bodyLength = HnswGraphCodec.vectorPayloadBytes(params.count(), params.dimension());
    URI uri = URI.create(trimSlash(serviceUrl) + BUILD_PATH);
    HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
    try {
      conn.setConnectTimeout(timeoutMillis);
      conn.setReadTimeout(timeoutMillis);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", CONTENT_TYPE_VECTORS);
      conn.setRequestProperty("Accept", CONTENT_TYPE_GRAPH);
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
      if (bearerToken != null && !bearerToken.isBlank()) {
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
      }
      conn.setFixedLengthStreamingMode(bodyLength);
      try (OutputStream out = conn.getOutputStream()) {
        HnswGraphCodec.writeVectors(out, vectors, params.dimension());
      }
      int status = conn.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        byte[] response;
        try (InputStream err = conn.getErrorStream()) {
          response = err == null ? new byte[0] : err.readAllBytes();
        }
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "HNSW build service returned HTTP "
                + status
                + ": "
                + new String(response, StandardCharsets.UTF_8));
      }
      try (InputStream in = conn.getInputStream()) {
        return HnswGraphCodec.decode(in);
      }
    } finally {
      conn.disconnect();
    }
  }

  public static byte[] encodeVectors(List<float[]> vectors, int dimension) throws IOException {
    return HnswGraphCodec.encodeVectors(vectors, dimension);
  }

  public static float[][] decodeVectors(byte[] body, int count, int dimension) throws IOException {
    return HnswGraphCodec.decodeVectors(body, count, dimension);
  }

  private static String trimSlash(String url) {
    if (url.endsWith("/")) {
      return url.substring(0, url.length() - 1);
    }
    return url;
  }
}
