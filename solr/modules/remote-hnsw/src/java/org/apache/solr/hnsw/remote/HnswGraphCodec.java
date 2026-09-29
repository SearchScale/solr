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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.packed.DirectMonotonicWriter;

/** Wire-format codec for {@link HnswNeighborGraph} and Lucene 99 `.vem`/`.vex` encoding. */
public final class HnswGraphCodec {
  static final int MAGIC = 0x52484E57; // 'RHNW'
  static final int VERSION = 1;
  static final int DIRECT_MONOTONIC_BLOCK_SHIFT = 16;

  /** Buffer size for streaming graph/vector I/O. */
  private static final int STREAM_BUFFER_SIZE = 1 << 16;

  private HnswGraphCodec() {}

  /**
   * Byte length of a FLOAT32 payload for {@code count} vectors of {@code dimension}. Uses {@code
   * long} so {@code count * dimension * 4} does not overflow an int.
   */
  public static long vectorPayloadBytes(int count, int dimension) {
    return (long) count * (long) dimension * Float.BYTES;
  }

  /** Encode graph; does not close {@code rawOut}. */
  public static void encode(OutputStream rawOut, HnswNeighborGraph graph) throws IOException {
    DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(rawOut, STREAM_BUFFER_SIZE));
    out.writeInt(MAGIC);
    out.writeInt(VERSION);
    out.writeInt(graph.maxConn());
    out.writeInt(graph.size());
    out.writeInt(graph.entryNode());
    out.writeInt(graph.numLevels());
    for (int level = 0; level < graph.numLevels(); level++) {
      int[] nodes = graph.nodesOnLevel(level);
      out.writeInt(nodes.length);
      if (level > 0) {
        for (int node : nodes) {
          out.writeInt(node);
        }
      }
      for (int i = 0; i < nodes.length; i++) {
        int[] nbrs = graph.neighbors(level, i);
        out.writeInt(nbrs.length);
        for (int nbr : nbrs) {
          out.writeInt(nbr);
        }
      }
    }
    out.flush();
  }

  public static byte[] encode(HnswNeighborGraph graph) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    encode(bos, graph);
    return bos.toByteArray();
  }

  /** Decode graph; does not close {@code rawIn}. */
  public static HnswNeighborGraph decode(InputStream rawIn) throws IOException {
    DataInputStream in = new DataInputStream(new BufferedInputStream(rawIn, STREAM_BUFFER_SIZE));
    int magic = in.readInt();
    if (magic != MAGIC) {
      throw new IOException("Not an RHNW graph (magic=" + Integer.toHexString(magic) + ")");
    }
    int version = in.readInt();
    if (version != VERSION) {
      throw new IOException("Unsupported RHNW graph version: " + version);
    }
    int maxConn = in.readInt();
    int size = in.readInt();
    int entryNode = in.readInt();
    int numLevels = in.readInt();
    int[][] levelNodes = new int[numLevels][];
    int[][][] neighbors = new int[numLevels][][];
    for (int level = 0; level < numLevels; level++) {
      int nodeCount = in.readInt();
      int[] nodes;
      if (level == 0) {
        nodes = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
          nodes[i] = i;
        }
        levelNodes[level] = null;
      } else {
        nodes = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
          nodes[i] = in.readInt();
        }
        levelNodes[level] = nodes;
      }
      neighbors[level] = new int[nodeCount][];
      for (int i = 0; i < nodeCount; i++) {
        int ncount = in.readInt();
        int[] nbrs = new int[ncount];
        for (int j = 0; j < ncount; j++) {
          nbrs[j] = in.readInt();
        }
        neighbors[level][i] = nbrs;
      }
    }
    return new HnswNeighborGraph(maxConn, size, entryNode, levelNodes, neighbors);
  }

  public static HnswNeighborGraph decode(byte[] bytes) throws IOException {
    return decode(new ByteArrayInputStream(bytes));
  }

  /**
   * Writes one field's HNSW graph into Lucene 99 `.vex` / `.vem` using the current segment outputs.
   * Returns per-level non-cumulative node lengths used for DirectMonotonic offsets.
   */
  static int[][] writeGraph(IndexOutput vectorIndex, HnswNeighborGraph graph) throws IOException {
    if (graph == null || graph.size() == 0 || graph.numLevels() == 0) {
      return new int[0][0];
    }
    int countOnLevel0 = graph.size();
    int[][] offsets = new int[graph.numLevels()][];
    for (int level = 0; level < graph.numLevels(); level++) {
      int[] sortedNodes = graph.nodesOnLevel(level);
      offsets[level] = new int[sortedNodes.length];
      int nodeOffsetId = 0;
      for (int i = 0; i < sortedNodes.length; i++) {
        int[] nnodes = graph.neighbors(level, i).clone();
        int size = nnodes.length;
        Arrays.sort(nnodes, 0, size);
        int[] scratch = new int[Math.max(graph.maxConn() * 2, size)];
        int actualSize = 0;
        if (size > 0) {
          scratch[0] = nnodes[0];
          actualSize = 1;
        }
        for (int n = 1; n < size; n++) {
          if (nnodes[n] >= countOnLevel0) {
            throw new IOException("node too large: " + nnodes[n] + ">=" + countOnLevel0);
          }
          if (nnodes[n - 1] == nnodes[n]) {
            continue;
          }
          scratch[actualSize++] = nnodes[n] - nnodes[n - 1];
        }
        long offsetStart = vectorIndex.getFilePointer();
        vectorIndex.writeVInt(actualSize);
        vectorIndex.writeGroupVInts(scratch, actualSize);
        offsets[level][nodeOffsetId++] =
            Math.toIntExact(vectorIndex.getFilePointer() - offsetStart);
      }
    }
    return offsets;
  }

  static void writeMeta(
      IndexOutput meta,
      IndexOutput vectorIndex,
      FieldInfo field,
      long vectorIndexOffset,
      long vectorIndexLength,
      int count,
      int defaultMaxConn,
      HnswNeighborGraph graph,
      int[][] graphLevelNodeOffsets)
      throws IOException {
    meta.writeInt(field.number);
    meta.writeInt(field.getVectorEncoding().ordinal());
    meta.writeInt(distFuncToOrd(field.getVectorSimilarityFunction()));
    meta.writeVLong(vectorIndexOffset);
    meta.writeVLong(vectorIndexLength);
    meta.writeVInt(field.getVectorDimension());
    meta.writeInt(count);
    if (graph == null || graph.numLevels() == 0) {
      meta.writeVInt(defaultMaxConn);
      meta.writeVInt(0);
      return;
    }
    meta.writeVInt(graph.maxConn());
    meta.writeVInt(graph.numLevels());
    long valueCount = 0;
    for (int level = 0; level < graph.numLevels(); level++) {
      int[] nodesOnLevel = graph.nodesOnLevel(level);
      valueCount += nodesOnLevel.length;
      if (level > 0) {
        int[] nol = nodesOnLevel.clone();
        Arrays.sort(nol);
        meta.writeVInt(nol.length);
        for (int i = nol.length - 1; i > 0; --i) {
          nol[i] -= nol[i - 1];
        }
        for (int n : nol) {
          meta.writeVInt(n);
        }
      }
    }
    long start = vectorIndex.getFilePointer();
    meta.writeLong(start);
    meta.writeVInt(DIRECT_MONOTONIC_BLOCK_SHIFT);
    DirectMonotonicWriter memoryOffsetsWriter =
        DirectMonotonicWriter.getInstance(
            meta, vectorIndex, valueCount, DIRECT_MONOTONIC_BLOCK_SHIFT);
    long cumulativeOffsetSum = 0;
    for (int[] levelOffsets : graphLevelNodeOffsets) {
      for (int v : levelOffsets) {
        memoryOffsetsWriter.add(cumulativeOffsetSum);
        cumulativeOffsetSum += v;
      }
    }
    memoryOffsetsWriter.finish();
    meta.writeLong(vectorIndex.getFilePointer() - start);
  }

  static int distFuncToOrd(VectorSimilarityFunction func) {
    var functions = Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS;
    for (int i = 0; i < functions.size(); i++) {
      if (functions.get(i).equals(func)) {
        return i;
      }
    }
    throw new IllegalArgumentException("invalid distance function: " + func);
  }

  /** Write little-endian FLOAT32 vectors; does not close {@code rawOut}. */
  public static void writeVectors(OutputStream rawOut, List<float[]> vectors, int dimension)
      throws IOException {
    OutputStream out = new BufferedOutputStream(rawOut, STREAM_BUFFER_SIZE);
    byte[] scratch = new byte[dimension * Float.BYTES];
    ByteBuffer bb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN);
    for (float[] vector : vectors) {
      if (vector.length != dimension) {
        throw new IllegalArgumentException(
            "vector dim " + vector.length + " != expected " + dimension);
      }
      bb.clear();
      for (float v : vector) {
        bb.putFloat(v);
      }
      out.write(scratch);
    }
    out.flush();
  }

  /** Read {@code count * dimension} little-endian FLOAT32s; does not close {@code rawIn}. */
  public static float[][] readVectors(InputStream rawIn, int count, int dimension)
      throws IOException {
    InputStream in = new BufferedInputStream(rawIn, STREAM_BUFFER_SIZE);
    byte[] scratch = new byte[dimension * Float.BYTES];
    ByteBuffer bb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN);
    float[][] vectors = new float[count][dimension];
    for (int i = 0; i < count; i++) {
      readFully(in, scratch);
      bb.clear();
      for (int d = 0; d < dimension; d++) {
        vectors[i][d] = bb.getFloat();
      }
    }
    return vectors;
  }

  private static void readFully(InputStream in, byte[] buf) throws IOException {
    int off = 0;
    while (off < buf.length) {
      int n = in.read(buf, off, buf.length - off);
      if (n < 0) {
        throw new IOException(
            "Unexpected EOF reading vector payload: needed " + buf.length + " bytes, got " + off);
      }
      off += n;
    }
  }

  public static byte[] encodeVectors(List<float[]> vectors, int dimension) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    writeVectors(bos, vectors, dimension);
    return bos.toByteArray();
  }

  public static float[][] decodeVectors(byte[] body, int count, int dimension) throws IOException {
    long expected = vectorPayloadBytes(count, dimension);
    if (body.length != expected) {
      throw new IllegalArgumentException(
          "vector payload length " + body.length + " != count*dim*4 " + expected);
    }
    return readVectors(new ByteArrayInputStream(body), count, dimension);
  }

  static int currentVersion() {
    return Lucene99HnswVectorsFormat.VERSION_CURRENT;
  }
}
