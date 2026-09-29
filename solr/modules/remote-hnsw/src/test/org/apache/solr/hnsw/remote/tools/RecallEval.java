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
package org.apache.solr.hnsw.remote.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;

/**
 * knn against a running core vs an `.ibin` ground-truth file (int32 header nq, k, then ids).
 *
 * <pre>
 * gradlew :solr:modules:remote-hnsw:evalRecall --args="\
 *   --base-url http://localhost:8983/solr/mycollection \
 *   --queries /path/to/queries.raw.f32 \
 *   --groundtruth /path/to/groundtruth.neighbors.ibin \
 *   --dim 768 --topk 10 --num-queries 1000"
 * </pre>
 */
public class RecallEval {

  public static void main(String[] args) throws Exception {
    String baseUrl = null;
    Path queriesFile = null;
    Path groundTruthFile = null;
    int dim = 768;
    int topK = 10;
    int numQueries = 1000;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--base-url" -> baseUrl = args[++i];
        case "--queries" -> queriesFile = Path.of(args[++i]);
        case "--groundtruth" -> groundTruthFile = Path.of(args[++i]);
        case "--dim" -> dim = Integer.parseInt(args[++i]);
        case "--topk" -> topK = Integer.parseInt(args[++i]);
        case "--num-queries" -> numQueries = Integer.parseInt(args[++i]);
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    if (baseUrl == null || queriesFile == null || groundTruthFile == null) {
      throw new IllegalArgumentException("--base-url, --queries and --groundtruth are required");
    }

    float[][] queries = readRawVectors(queriesFile, dim, numQueries);
    int[][] groundTruth = readGroundTruth(groundTruthFile, numQueries, topK);

    try (SolrClient client = new HttpJdkSolrClient.Builder(baseUrl).build()) {
      double totalRecall = 0;
      long totalLatencyNanos = 0;
      for (int q = 0; q < numQueries; q++) {
        StringBuilder vec = new StringBuilder("[");
        for (int d = 0; d < dim; d++) {
          if (d > 0) vec.append(',');
          vec.append(queries[q][d]);
        }
        vec.append(']');

        SolrParams params =
            new SolrQuery()
                .setQuery("{!knn f=vector topK=" + topK + "}" + vec)
                .setFields("id")
                .setRows(topK);
        QueryRequest req = new QueryRequest(params);
        req.setMethod(SolrRequest.METHOD.POST);

        long t0 = System.nanoTime();
        QueryResponse rsp = req.process(client);
        long latencyNanos = System.nanoTime() - t0;
        totalLatencyNanos += latencyNanos;

        SolrDocumentList results = rsp.getResults();
        Set<Integer> returned = new HashSet<>();
        for (SolrDocument doc : results) {
          returned.add(Integer.parseInt(doc.getFieldValue("id").toString()));
        }
        int hits = 0;
        for (int i = 0; i < topK; i++) {
          if (returned.contains(groundTruth[q][i])) {
            hits++;
          }
        }
        totalRecall += (double) hits / topK;

        if ((q + 1) % 100 == 0) {
          System.out.printf(
              Locale.ROOT,
              "query %d/%d running recall@%d=%.4f%n",
              q + 1,
              numQueries,
              topK,
              totalRecall / (q + 1));
        }
      }

      System.out.printf(
          Locale.ROOT,
          "%nrecall@%d over %d queries: %.4f%n",
          topK,
          numQueries,
          totalRecall / numQueries);
      System.out.printf(
          Locale.ROOT,
          "avg query latency: %.2f ms%n",
          (totalLatencyNanos / (double) numQueries) / 1e6);
    }
  }

  private static float[][] readRawVectors(Path file, int dim, int count) throws Exception {
    float[][] vectors = new float[count][dim];
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer buf =
          ByteBuffer.allocate(count * dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
      readFully(channel, buf);
      buf.flip();
      for (int i = 0; i < count; i++) {
        buf.asFloatBuffer().position(i * dim).get(vectors[i], 0, dim);
      }
    }
    return vectors;
  }

  /** *.ibin ground truth: 8-byte header (int32 numQueries, int32 k), then row-major int32 ids. */
  private static int[][] readGroundTruth(Path file, int numQueries, int topK) throws Exception {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      readFully(channel, header);
      header.flip();
      int fileNumQueries = header.getInt();
      int fileK = header.getInt();
      if (topK > fileK) {
        throw new IllegalArgumentException(
            "requested topK=" + topK + " exceeds ground truth k=" + fileK);
      }
      if (numQueries > fileNumQueries) {
        throw new IllegalArgumentException(
            "requested numQueries=" + numQueries + " exceeds ground truth rows=" + fileNumQueries);
      }
      int[][] groundTruth = new int[numQueries][topK];
      ByteBuffer row = ByteBuffer.allocate(fileK * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
      for (int q = 0; q < numQueries; q++) {
        row.clear();
        readFully(channel, row);
        row.flip();
        for (int i = 0; i < topK; i++) {
          groundTruth[q][i] = row.getInt();
        }
      }
      return groundTruth;
    }
  }

  private static void readFully(FileChannel channel, ByteBuffer buf) throws Exception {
    while (buf.hasRemaining()) {
      int n = channel.read(buf);
      if (n < 0) {
        throw new java.io.IOException("unexpected EOF");
      }
    }
  }

  private RecallEval() {}
}
