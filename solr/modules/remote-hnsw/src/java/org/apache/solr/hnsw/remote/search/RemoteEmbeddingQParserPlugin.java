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
package org.apache.solr.hnsw.remote.search;

import java.io.IOException;
import java.util.List;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.hnsw.remote.RemoteEmbeddingClient;
import org.apache.solr.hnsw.remote.RemoteHnswCodecFactory;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.vector.KnnQParser;

/**
 * Query parser that embeds the query string via {@code POST /v1/embed} and then runs the same kNN
 * search as {@code {!knn}}.
 *
 * <pre>{@code
 * <queryParser name="remote_embed" class="org.apache.solr.hnsw.remote.search.RemoteEmbeddingQParserPlugin">
 *   <str name="serviceUrl">${solr.embed.service.url:}</str>
 *   <str name="model">minilm</str>
 *   <int name="timeoutSeconds">30</int>
 * </queryParser>
 * }</pre>
 *
 * Query: {@code q={!remote_embed f=vector topK=5}quick brown fox}
 */
public class RemoteEmbeddingQParserPlugin extends QParserPlugin {
  static final String SERVICE_URL_PARAM = "serviceUrl";
  static final String MODEL_PARAM = "model";
  static final String TIMEOUT_SECONDS_PARAM = "timeoutSeconds";
  static final String BEARER_TOKEN_PARAM = "bearerToken";
  static final int DEFAULT_TIMEOUT_SECONDS = 60;

  private RemoteEmbeddingClient client;
  private String defaultModel;

  @Override
  public void init(NamedList<?> args) {
    SolrParams params = args.toSolrParams();
    String serviceUrl =
        RemoteHnswCodecFactory.resolveEmbedServiceUrl(params.get(SERVICE_URL_PARAM));
    if (serviceUrl == null) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          SERVICE_URL_PARAM
              + " is required (or set SOLR_EMBED_SERVICE_URL / SOLR_HNSW_BUILD_SERVICE_URL)");
    }
    defaultModel = params.required().get(MODEL_PARAM);
    int timeoutSeconds = params.getInt(TIMEOUT_SECONDS_PARAM, DEFAULT_TIMEOUT_SECONDS);
    client = new RemoteEmbeddingClient(serviceUrl, params.get(BEARER_TOKEN_PARAM), timeoutSeconds);
  }

  @Override
  public QParser createParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new RemoteEmbeddingQParser(qstr, localParams, params, req);
  }

  final class RemoteEmbeddingQParser extends KnnQParser {
    RemoteEmbeddingQParser(
        String queryString, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
      super(queryString, localParams, params, req);
    }

    @Override
    public Query parse() throws SyntaxError {
      if (qstr == null || qstr.isBlank()) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST, "remote_embed query string is empty");
      }
      String model = localParams.get(MODEL_PARAM, defaultModel);
      float[] vector;
      try {
        vector = client.embed(model, List.of(qstr))[0];
      } catch (IOException e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Remote embedding of query failed (model=" + model + "): " + e.getMessage(),
            e);
      }
      this.vectorToSearch = toVectorLiteral(vector);
      return super.parse();
    }
  }

  static String toVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(vector[i]);
    }
    sb.append(']');
    return sb.toString();
  }
}
