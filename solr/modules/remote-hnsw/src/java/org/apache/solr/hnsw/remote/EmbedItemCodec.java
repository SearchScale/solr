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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-prefixed UTF-8 items for {@code /v1/embed} request bodies. The response reuses {@link
 * HnswGraphCodec#writeVectors}.
 */
public final class EmbedItemCodec {
  private static final int STREAM_BUFFER_SIZE = 1 << 16;

  private EmbedItemCodec() {}

  /** Streaming write; does not close {@code rawOut}. */
  public static void writeTextItems(OutputStream rawOut, List<String> items) throws IOException {
    DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(rawOut, STREAM_BUFFER_SIZE));
    for (String item : items) {
      byte[] bytes = item == null ? new byte[0] : item.getBytes(StandardCharsets.UTF_8);
      out.writeInt(bytes.length);
      out.write(bytes);
    }
    out.flush();
  }

  /** Streaming read of exactly {@code count} items; does not close {@code rawIn}. */
  public static List<String> readTextItems(InputStream rawIn, int count) throws IOException {
    DataInputStream in = new DataInputStream(new BufferedInputStream(rawIn, STREAM_BUFFER_SIZE));
    List<String> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int len = in.readInt();
      if (len < 0) {
        throw new IOException("negative item length: " + len);
      }
      byte[] bytes = new byte[len];
      in.readFully(bytes);
      items.add(new String(bytes, StandardCharsets.UTF_8));
    }
    return items;
  }
}
