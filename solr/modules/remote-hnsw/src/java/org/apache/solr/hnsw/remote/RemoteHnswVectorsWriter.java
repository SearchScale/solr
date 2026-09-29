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

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatFieldVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorsWriter;
import org.apache.lucene.index.DocsWithFieldSet;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.RamUsageEstimator;

/** Buffers FLOAT32 vectors, builds the graph remotely, writes Lucene 99 `.vem`/`.vex`. */
public final class RemoteHnswVectorsWriter extends KnnVectorsWriter {
  private static final long SHALLOW_RAM_BYTES_USED =
      RamUsageEstimator.shallowSizeOfInstance(RemoteHnswVectorsWriter.class);

  private final FlatVectorsWriter flatVectorsWriter;
  private final RemoteHnswClient client;
  private final HnswBuildParams buildParams;
  private final boolean fallbackToCpu;
  private final IndexOutput meta;
  private final IndexOutput vectorIndex;
  private final List<FieldWriter> fields = new ArrayList<>();
  private boolean finished;

  public RemoteHnswVectorsWriter(
      SegmentWriteState state,
      FlatVectorsWriter flatVectorsWriter,
      RemoteHnswClient client,
      HnswBuildParams buildParams,
      boolean fallbackToCpu)
      throws IOException {
    this.flatVectorsWriter = flatVectorsWriter;
    this.client = client;
    this.buildParams = buildParams;
    this.fallbackToCpu = fallbackToCpu;

    String metaFileName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, "vem");
    String indexDataFileName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, "vex");
    try {
      meta = state.directory.createOutput(metaFileName, state.context);
      vectorIndex = state.directory.createOutput(indexDataFileName, state.context);
      int version = HnswGraphCodec.currentVersion();
      CodecUtil.writeIndexHeader(
          meta,
          "Lucene99HnswVectorsFormatMeta",
          version,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      CodecUtil.writeIndexHeader(
          vectorIndex,
          "Lucene99HnswVectorsFormatIndex",
          version,
          state.segmentInfo.getId(),
          state.segmentSuffix);
    } catch (Throwable t) {
      IOUtils.closeWhileHandlingException(this);
      throw t;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
    if (fieldInfo.getVectorEncoding() != VectorEncoding.FLOAT32) {
      throw new IllegalArgumentException(
          "remote_hnsw only supports FLOAT32 vectors, got " + fieldInfo.getVectorEncoding());
    }
    FlatFieldVectorsWriter<float[]> flat =
        (FlatFieldVectorsWriter<float[]>) flatVectorsWriter.addField(fieldInfo);
    FieldWriter writer = new FieldWriter(fieldInfo, flat);
    fields.add(writer);
    return flat;
  }

  @Override
  public void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
    flatVectorsWriter.flush(maxDoc, sortMap);
    for (FieldWriter field : fields) {
      List<float[]> vectors = vectorsInOrdinalOrder(field, sortMap);
      writeField(field.fieldInfo, vectors);
    }
  }

  @Override
  public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
    flatVectorsWriter.mergeOneField(fieldInfo, mergeState);
    FloatVectorValues merged =
        KnnVectorsWriter.MergedVectorValues.mergeFloatVectorValues(fieldInfo, mergeState);
    List<float[]> vectors = new ArrayList<>();
    KnnVectorValues.DocIndexIterator it = merged.iterator();
    for (int doc = it.nextDoc(); doc != NO_MORE_DOCS; doc = it.nextDoc()) {
      vectors.add(merged.vectorValue(it.index()).clone());
    }
    writeField(fieldInfo, vectors);
  }

  private void writeField(FieldInfo fieldInfo, List<float[]> vectors) throws IOException {
    HnswNeighborGraph graph = buildGraph(fieldInfo, vectors);
    long vectorIndexOffset = vectorIndex.getFilePointer();
    int[][] offsets = HnswGraphCodec.writeGraph(vectorIndex, graph);
    long vectorIndexLength = vectorIndex.getFilePointer() - vectorIndexOffset;
    HnswGraphCodec.writeMeta(
        meta,
        vectorIndex,
        fieldInfo,
        vectorIndexOffset,
        vectorIndexLength,
        vectors.size(),
        buildParams.hnswM(),
        graph,
        offsets);
  }

  private HnswNeighborGraph buildGraph(FieldInfo fieldInfo, List<float[]> vectors)
      throws IOException {
    if (vectors.isEmpty()) {
      return HnswNeighborGraph.empty(buildParams.hnswM());
    }
    if (vectors.size() == 1) {
      return HnswNeighborGraph.singleNode(buildParams.hnswM());
    }
    HnswBuildParams params = buildParams.withCount(vectors.size());
    try {
      if (client == null) {
        throw new IOException("HNSW build serviceUrl is not configured");
      }
      return client.build(vectors, params);
    } catch (Exception e) {
      if (fallbackToCpu) {
        return LuceneCpuHnswBuilder.build(
            vectors,
            fieldInfo.getVectorSimilarityFunction(),
            params.hnswM(),
            params.hnswEfConstruction());
      }
      if (e instanceof IOException io) {
        throw io;
      }
      throw new IOException("Remote HNSW build failed for field " + fieldInfo.name, e);
    }
  }

  private static List<float[]> vectorsInOrdinalOrder(FieldWriter field, Sorter.DocMap sortMap)
      throws IOException {
    List<float[]> vectors = field.flat.getVectors();
    if (sortMap == null) {
      return vectors;
    }
    DocsWithFieldSet docsWithField = field.flat.getDocsWithFieldSet();
    int[] newToOldOrd = new int[docsWithField.cardinality()];
    mapOldOrdToNewOrd(docsWithField, sortMap, null, newToOldOrd, null);
    List<float[]> sorted = new ArrayList<>(vectors.size());
    for (int i = 0; i < vectors.size(); i++) {
      sorted.add(vectors.get(newToOldOrd[i]));
    }
    return sorted;
  }

  @Override
  public void finish() throws IOException {
    if (finished) {
      throw new IllegalStateException("already finished");
    }
    finished = true;
    flatVectorsWriter.finish();
    if (meta != null) {
      meta.writeInt(-1);
      CodecUtil.writeFooter(meta);
    }
    if (vectorIndex != null) {
      CodecUtil.writeFooter(vectorIndex);
    }
  }

  @Override
  public long ramBytesUsed() {
    return SHALLOW_RAM_BYTES_USED + flatVectorsWriter.ramBytesUsed();
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(meta, vectorIndex, flatVectorsWriter);
  }

  private static final class FieldWriter {
    private final FieldInfo fieldInfo;
    private final FlatFieldVectorsWriter<float[]> flat;

    private FieldWriter(FieldInfo fieldInfo, FlatFieldVectorsWriter<float[]> flat) {
      this.fieldInfo = fieldInfo;
      this.flat = flat;
    }
  }
}
