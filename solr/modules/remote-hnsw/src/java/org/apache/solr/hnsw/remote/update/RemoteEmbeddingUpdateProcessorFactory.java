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
package org.apache.solr.hnsw.remote.update;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.hnsw.remote.RemoteEmbeddingClient;
import org.apache.solr.hnsw.remote.RemoteHnswCodecFactory;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

/**
 * Turns a text field into a vector field via {@code POST /v1/embed}, one HTTP call per batch.
 *
 * <pre>{@code
 * <processor class="org.apache.solr.hnsw.remote.update.RemoteEmbeddingUpdateProcessorFactory">
 *   <str name="serviceUrl">${solr.embed.service.url:}</str>
 *   <str name="sourceField">content</str>
 *   <str name="targetField">vector</str>
 *   <str name="model">hash-demo</str>
 *   <int name="batchSize">500</int>
 * </processor>
 * }</pre>
 *
 * Missing {@code sourceField} is left unchanged. A failed embed rejects the whole batch.
 */
public class RemoteEmbeddingUpdateProcessorFactory extends UpdateRequestProcessorFactory {
  static final String SERVICE_URL_PARAM = "serviceUrl";
  static final String SOURCE_FIELD_PARAM = "sourceField";
  static final String TARGET_FIELD_PARAM = "targetField";
  static final String MODEL_PARAM = "model";
  static final String BATCH_SIZE_PARAM = "batchSize";
  static final String TIMEOUT_SECONDS_PARAM = "timeoutSeconds";
  static final String BEARER_TOKEN_PARAM = "bearerToken";

  static final int DEFAULT_BATCH_SIZE = 500;
  static final int DEFAULT_TIMEOUT_SECONDS = 60;

  private String sourceField;
  private String targetField;
  private String model;
  private int batchSize;
  private RemoteEmbeddingClient client;

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
    sourceField = params.required().get(SOURCE_FIELD_PARAM);
    targetField = params.required().get(TARGET_FIELD_PARAM);
    model = params.required().get(MODEL_PARAM);
    batchSize = params.getInt(BATCH_SIZE_PARAM, DEFAULT_BATCH_SIZE);
    int timeoutSeconds = params.getInt(TIMEOUT_SECONDS_PARAM, DEFAULT_TIMEOUT_SECONDS);
    String bearerToken = params.get(BEARER_TOKEN_PARAM);
    if (batchSize <= 0) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, BATCH_SIZE_PARAM + " must be > 0");
    }
    client = new RemoteEmbeddingClient(serviceUrl, bearerToken, timeoutSeconds);
  }

  @Override
  public UpdateRequestProcessor getInstance(
      SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
    return new RemoteEmbeddingUpdateProcessor(
        next, client, model, sourceField, targetField, batchSize);
  }
}
