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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.apache.solr.SolrTestCase;
import org.junit.Test;

public class EmbedItemCodecTest extends SolrTestCase {

  @Test
  public void testRoundTrip() throws Exception {
    List<String> items =
        Arrays.asList("hello world", "", "unicode: \u00e9\u00e8\u00ea", "one more item");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    EmbedItemCodec.writeTextItems(bos, items);
    List<String> decoded =
        EmbedItemCodec.readTextItems(new ByteArrayInputStream(bos.toByteArray()), items.size());
    assertEquals(items, decoded);
  }

  @Test
  public void testNullItemRoundTripsAsEmptyString() throws Exception {
    List<String> items = Arrays.asList("a", null, "b");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    EmbedItemCodec.writeTextItems(bos, items);
    List<String> decoded =
        EmbedItemCodec.readTextItems(new ByteArrayInputStream(bos.toByteArray()), items.size());
    assertEquals(Arrays.asList("a", "", "b"), decoded);
  }
}
