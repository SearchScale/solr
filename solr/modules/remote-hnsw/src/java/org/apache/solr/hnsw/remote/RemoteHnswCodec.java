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

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.DenseVectorField;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;

/** {@link FilterCodec} for {@code knnAlgorithm="remote_hnsw"} fields. */
public class RemoteHnswCodec extends FilterCodec {
  private static final String FALLBACK_CODEC = "Lucene104";

  private final SolrCore core;
  private final Lucene104Codec fallbackCodec;
  private final RemoteHnswClient client;
  private final boolean fallbackToCpu;
  private final HnswBuilder builder;

  public RemoteHnswCodec(
      SolrCore core,
      Lucene104Codec fallback,
      RemoteHnswClient client,
      boolean fallbackToCpu,
      HnswBuilder builder) {
    super(FALLBACK_CODEC, fallback);
    this.core = core;
    this.fallbackCodec = fallback;
    this.client = client;
    this.fallbackToCpu = fallbackToCpu;
    this.builder = builder == null ? HnswBuilder.AUTO : builder;
  }

  @Override
  public KnnVectorsFormat knnVectorsFormat() {
    return perFieldKnnVectorsFormat;
  }

  private final PerFieldKnnVectorsFormat perFieldKnnVectorsFormat =
      new PerFieldKnnVectorsFormat() {
        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
          SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
          FieldType fieldType = schemaField == null ? null : schemaField.getType();
          if (fieldType instanceof DenseVectorField vectorType) {
            if (DenseVectorField.REMOTE_HNSW_ALGORITHM.equals(vectorType.getKnnAlgorithm())) {
              HnswBuildParams params =
                  new HnswBuildParams(
                      vectorType.getDimension(),
                      0,
                      vectorType.getSimilarityFunction().name(),
                      vectorType.getHnswM(),
                      vectorType.getHnswEfConstruction(),
                      vectorType.getCuvsWriterThreads(),
                      vectorType.getCuvsIntGraphDegree(),
                      vectorType.getCuvsGraphDegree(),
                      vectorType.getCuvsHnswLayers(),
                      vectorType.getCuvsCagraGraphBuildAlgo(),
                      builder);
              return new RemoteHnswVectorsFormat(client, params, fallbackToCpu);
            }
            if (DenseVectorField.HNSW_ALGORITHM.equals(vectorType.getKnnAlgorithm())
                || DenseVectorField.FLAT_ALGORITHM.equals(vectorType.getKnnAlgorithm())) {
              return fallbackCodec.getKnnVectorsFormatForField(field);
            }
            throw new SolrException(
                SolrException.ErrorCode.SERVER_ERROR,
                vectorType.getKnnAlgorithm() + " KNN algorithm is not supported");
          }
          return fallbackCodec.getKnnVectorsFormatForField(field);
        }
      };
}
