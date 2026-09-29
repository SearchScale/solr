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

import static org.apache.solr.hnsw.remote.EmbedProtocol.CONTENT_TYPE_EMBED_ITEMS;
import static org.apache.solr.hnsw.remote.EmbedProtocol.EMBED_PATH;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_COUNT;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_DIMENSION;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_KIND;
import static org.apache.solr.hnsw.remote.EmbedProtocol.HDR_EMBED_MODEL;
import static org.apache.solr.hnsw.remote.EmbedProtocol.KIND_TEXT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.apache.solr.common.SolrException;

/** HTTP client for {@code POST /v1/embed}. */
public final class RemoteEmbeddingClient {
  private final String serviceUrl;
  private final String bearerToken;
  private final int timeoutMillis;

  public RemoteEmbeddingClient(String serviceUrl, String bearerToken, int timeoutSeconds) {
    this.serviceUrl = Objects.requireNonNull(serviceUrl, "serviceUrl");
    this.bearerToken = bearerToken;
    this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
  }

  /**
   * Embeds a batch of texts against the given model id; returns one vector per input text, in the
   * same order.
   */
  public float[][] embed(String modelId, List<String> texts) throws IOException {
    URI uri = URI.create(trimSlash(serviceUrl) + EMBED_PATH);
    HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
    try {
      conn.setConnectTimeout(timeoutMillis);
      conn.setReadTimeout(timeoutMillis);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", CONTENT_TYPE_EMBED_ITEMS);
      conn.setRequestProperty(HDR_EMBED_MODEL, modelId);
      conn.setRequestProperty(HDR_EMBED_KIND, KIND_TEXT);
      conn.setRequestProperty(HDR_EMBED_COUNT, Integer.toString(texts.size()));
      if (bearerToken != null && !bearerToken.isBlank()) {
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
      }
      conn.setChunkedStreamingMode(1 << 16);
      try (OutputStream out = conn.getOutputStream()) {
        EmbedItemCodec.writeTextItems(out, texts);
      }

      int status = conn.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        byte[] response;
        try (InputStream err = conn.getErrorStream()) {
          response = err == null ? new byte[0] : err.readAllBytes();
        }
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Embedding service returned HTTP "
                + status
                + ": "
                + new String(response, StandardCharsets.UTF_8));
      }

      int dimension = Integer.parseInt(header(conn, HDR_EMBED_DIMENSION));
      try (InputStream in = conn.getInputStream()) {
        return HnswGraphCodec.readVectors(in, texts.size(), dimension);
      }
    } finally {
      conn.disconnect();
    }
  }

  private static String header(HttpURLConnection conn, String name) {
    String v = conn.getHeaderField(name);
    if (v == null || v.isBlank()) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Embedding service response missing header " + name);
    }
    return v;
  }

  private static String trimSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
