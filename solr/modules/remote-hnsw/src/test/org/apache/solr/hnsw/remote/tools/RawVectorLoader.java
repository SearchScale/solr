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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.common.SolrInputDocument;

/**
 * Streams a headerless little-endian float32 file into a running Solr core.
 *
 * <pre>
 * gradlew :solr:modules:remote-hnsw:loadRawVectors --args="\
 *   --base-url http://localhost:8983/solr/mycollection \
 *   --file /path/to/base.raw.f32 --dim 768 \
 *   --batch-size 2000 --commit-every 1000000"
 * </pre>
 */
public class RawVectorLoader {

  public static void main(String[] args) throws Exception {
    String baseUrl = null;
    Path file = null;
    int dim = 768;
    long start = 0;
    long count = -1;
    int batchSize = 2000;
    long commitEvery = 1_000_000;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--base-url" -> baseUrl = args[++i];
        case "--file" -> file = Path.of(args[++i]);
        case "--dim" -> dim = Integer.parseInt(args[++i]);
        case "--start" -> start = Long.parseLong(args[++i]);
        case "--count" -> count = Long.parseLong(args[++i]);
        case "--batch-size" -> batchSize = Integer.parseInt(args[++i]);
        case "--commit-every" -> commitEvery = Long.parseLong(args[++i]);
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    if (baseUrl == null || file == null) {
      throw new IllegalArgumentException("--base-url and --file are required");
    }

    long rowBytes = (long) dim * Float.BYTES;
    long fileSize = java.nio.file.Files.size(file);
    long totalRows = fileSize / rowBytes;
    long endRow = count < 0 ? totalRows : Math.min(totalRows, start + count);

    System.out.println(
        "RawVectorLoader: file="
            + file
            + " dim="
            + dim
            + " totalRowsInFile="
            + totalRows
            + " loading rows ["
            + start
            + ","
            + endRow
            + ") -> "
            + baseUrl);

    try (SolrClient client =
            new HttpJdkSolrClient.Builder(baseUrl)
                .withRequestTimeout(3600, TimeUnit.SECONDS)
                .withIdleTimeout(3600, TimeUnit.SECONDS)
                .build();
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      channel.position(start * rowBytes);
      ByteBuffer buf =
          ByteBuffer.allocateDirect(batchSize * dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

      long row = start;
      long sinceCommit = 0;
      long t0 = System.nanoTime();
      long lastLogRow = start;
      long lastLogNanos = t0;

      while (row < endRow) {
        int rowsThisBatch = (int) Math.min(batchSize, endRow - row);
        buf.clear();
        buf.limit(rowsThisBatch * dim * Float.BYTES);
        readFully(channel, buf);
        buf.flip();

        List<SolrInputDocument> docs = new ArrayList<>(rowsThisBatch);
        for (int r = 0; r < rowsThisBatch; r++) {
          float[] vector = new float[dim];
          buf.asFloatBuffer().position(r * dim).get(vector, 0, dim);
          SolrInputDocument doc = new SolrInputDocument();
          doc.addField("id", Long.toString(row + r));
          doc.addField("vector", vector);
          docs.add(doc);
        }
        client.add(docs);
        row += rowsThisBatch;
        sinceCommit += rowsThisBatch;

        if (sinceCommit >= commitEvery) {
          System.out.println("Committing at row " + row + " ...");
          client.commit();
          sinceCommit = 0;
        }

        long now = System.nanoTime();
        if (now - lastLogNanos > 10_000_000_000L) {
          double ratePerSec = (row - lastLogRow) / ((now - lastLogNanos) / 1e9);
          double elapsedMin = (now - t0) / 1e9 / 60.0;
          System.out.printf(
              Locale.ROOT,
              "row=%d/%d (%.1f%%) rate=%.0f docs/s elapsed=%.1fmin%n",
              row,
              endRow,
              100.0 * row / endRow,
              ratePerSec,
              elapsedMin);
          lastLogRow = row;
          lastLogNanos = now;
        }
      }

      System.out.println("Final commit...");
      client.commit();
      double totalMin = (System.nanoTime() - t0) / 1e9 / 60.0;
      System.out.printf(
          Locale.ROOT, "Done. Loaded %d rows in %.1f min.%n", endRow - start, totalMin);
    }
  }

  private static void readFully(FileChannel channel, ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      int n = channel.read(buf);
      if (n < 0) {
        throw new IOException("unexpected EOF while reading vectors");
      }
    }
  }

  private RawVectorLoader() {}
}
