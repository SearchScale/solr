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
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/**
 * Writes HNSW graphs via a remote build service and reads them with {@link
 * Lucene99HnswVectorsReader}. The on-disk name is {@code Lucene99HnswVectorsFormat} so a later
 * searcher can open the segment via Lucene's built-in SPI (module classloaders do not register
 * custom KnnVectorsFormat names with Lucene's NamedSPILoader).
 */
public final class RemoteHnswVectorsFormat extends KnnVectorsFormat {
  public static final String NAME = "Lucene99HnswVectorsFormat";
  private static final int MAX_DIMENSIONS = 4096;

  private static final Lucene99FlatVectorsFormat FLAT_FORMAT =
      new Lucene99FlatVectorsFormat(FlatVectorScorerUtil.getLucene99FlatVectorsScorer());

  private final RemoteHnswClient client;
  private final HnswBuildParams buildParams;
  private final boolean fallbackToCpu;

  /** SPI constructor used when opening existing segments. */
  public RemoteHnswVectorsFormat() {
    this(null, null, false);
  }

  public RemoteHnswVectorsFormat(
      RemoteHnswClient client, HnswBuildParams buildParams, boolean fallbackToCpu) {
    super(NAME);
    this.client = client;
    this.buildParams = buildParams;
    this.fallbackToCpu = fallbackToCpu;
  }

  @Override
  public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
    if (buildParams == null) {
      throw new IllegalStateException(
          "RemoteHnswVectorsFormat was loaded for reading; indexing requires RemoteHnswCodecFactory");
    }
    return new RemoteHnswVectorsWriter(
        state, FLAT_FORMAT.fieldsWriter(state), client, buildParams, fallbackToCpu);
  }

  @Override
  public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
    return new Lucene99HnswVectorsReader(state, FLAT_FORMAT.fieldsReader(state));
  }

  @Override
  public int getMaxDimensions(String fieldName) {
    return MAX_DIMENSIONS;
  }
}
