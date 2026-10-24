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
package org.apache.solr.luxir.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.util.JsonRecordReader;
import org.apache.solr.common.util.Utils;
import org.junit.Test;
import org.noggit.JSONParser;

/** The fast JSON parser agrees with Solr's JSON parser, including escapes and numbers. */
public class LuxirFastJsonParserTest extends SolrTestCase {
  private static final String[] PIECES = {
    "a",
    "é",
    "日本",
    "😀",
    "\\\\",
    "\\\"",
    "\\n",
    "\\t",
    "\\u00e9",
    "\\ud83d\\ude00",
    "\\/",
    " ",
    "xyzw1234"
  };

  private static final String[] BARE = {
    "Infinity", "NaN", "trueX", "nullable", "falsey", "abc.def-1/x", "_x$1", "true", "null"
  };

  private static String str(Random r) {
    StringBuilder sb = new StringBuilder("\"");
    int n = r.nextInt(r.nextInt(5) == 0 ? 200 : 12);
    for (int i = 0; i < n; i++) sb.append(PIECES[r.nextInt(PIECES.length)]);
    return sb.append('"').toString();
  }

  private static String value(Random r) {
    if (r.nextInt(40) == 0) return "12345678901234567890"; // beyond a long: left to Solr's loader
    return switch (r.nextInt(10)) {
      case 0 -> Long.toString(r.nextLong());
      case 1 -> Integer.toString(r.nextInt(1000) - 500);
      case 2 -> Double.toString(r.nextGaussian() * 1e6);
      case 3 -> r.nextBoolean() ? "true" : "false";
      case 4 -> "null";
      case 5 -> "-0.5e-3";
      case 6 -> BARE[r.nextInt(BARE.length)]; // unquoted words, read by Solr's parser as strings
      default -> str(r);
    };
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSameAsSolrParser() {
    Random r = new Random(random().nextLong());
    int fastParsed = 0;
    for (int iter = 0; iter < 300; iter++) {
      StringBuilder json = new StringBuilder("[");
      int docs = 1 + r.nextInt(5);
      for (int d = 0; d < docs; d++) {
        if (d > 0) json.append(",");
        json.append(r.nextBoolean() ? "{" : " {\n ");
        int fields = r.nextInt(8);
        for (int f = 0; f < fields; f++) {
          if (f > 0) json.append(", ");
          // keys are unique within a document (Solr's parser keeps the last of repeated keys)
          json.append(r.nextInt(6) == 0 ? "\"k" + f + str(r).substring(1) : "\"f" + f + "\"")
              .append(':');
          if (r.nextInt(4) == 0) {
            json.append('[');
            int k = r.nextInt(4);
            for (int i = 0; i < k; i++) json.append(i > 0 ? "," : "").append(value(r));
            json.append(']');
          } else {
            json.append(value(r));
          }
        }
        json.append('}');
      }
      json.append("]");
      byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
      List<FastJsonDocsLoader.Parsed> fast = new FastJsonDocsLoader.Parser(bytes).docs();
      List<Map<String, Object>> expected =
          (List<Map<String, Object>>) Utils.fromJSONString(json.toString());
      if (fast == null) continue; // left to Solr's loader (e.g. a number beyond a long)
      fastParsed++;
      assertEquals(json.toString(), expected.size(), fast.size());
      for (int i = 0; i < fast.size(); i++) {
        assertEquals(json.toString(), normalize(expected.get(i)), render(fast.get(i)));
      }
    }
    assertTrue(fastParsed > 100);
  }

  /**
   * As /update/json/docs reads them: documents one after another or in arrays, split at the root.
   */
  @Test
  public void testDocStreamSameAsSolrRecordReader() throws Exception {
    Random r = new Random(random().nextLong());
    JsonRecordReader reader = JsonRecordReader.getInst("/", List.of("$FQN:/**"));
    int fastParsed = 0;
    for (int iter = 0; iter < 300; iter++) {
      StringBuilder json = new StringBuilder(r.nextBoolean() ? "" : "\n");
      int groups = r.nextInt(4);
      for (int g = 0; g < groups; g++) {
        boolean array = r.nextInt(3) == 0;
        int docs = (array ? 0 : 1) + r.nextInt(3);
        if (array) json.append("[");
        for (int d = 0; d < docs; d++) {
          if (array && d > 0) json.append(",");
          json.append(r.nextBoolean() ? "{" : " {\n ");
          int fields = r.nextInt(8);
          for (int f = 0; f < fields; f++) {
            if (f > 0) json.append(", ");
            // a repeated key adds values, as an array does
            String key = r.nextInt(10) == 0 && f > 0 ? "f0" : "f" + f;
            json.append(r.nextInt(6) == 0 ? "\"k" + f + str(r).substring(1) : "\"" + key + "\"")
                .append(':');
            if (r.nextInt(4) == 0) {
              json.append('[');
              int k = r.nextInt(4);
              for (int i = 0; i < k; i++) json.append(i > 0 ? "," : "").append(value(r));
              json.append(']');
            } else {
              json.append(value(r));
            }
          }
          json.append('}');
        }
        if (array) json.append("]");
        json.append(r.nextBoolean() ? "\n" : " ");
      }
      List<Map<String, Object>> expected = new ArrayList<>();
      reader.streamRecords(
          new JSONParser(json.toString()),
          (record, path) -> expected.add(new LinkedHashMap<>(record)));
      byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
      List<FastJsonDocsLoader.Parsed> fast = new FastJsonDocsLoader.Parser(bytes).docStream();
      if (fast == null) continue; // left to Solr's loader (e.g. a number beyond a long)
      fastParsed++;
      assertEquals(json.toString(), expected.size(), fast.size());
      for (int i = 0; i < fast.size(); i++) {
        assertEquals(json.toString(), normalize(expected.get(i)), render(fast.get(i)));
      }
    }
    assertTrue(fastParsed > 100);
  }

  @Test
  public void testDocStreamLeavesNestedToSolr() {
    for (String json :
        new String[] {
          "{\"a\":{\"b\":1}}", "{\"a\":[{\"b\":1}]}", "[1,2]", "\"x\"", "{\"a\":1} x"
        }) {
      assertNull(
          json, new FastJsonDocsLoader.Parser(json.getBytes(StandardCharsets.UTF_8)).docStream());
    }
  }

  /** Solr's parse with nulls dropped and values as strings, arrays flattened (repeated keys). */
  private static Map<String, List<String>> normalize(Map<String, Object> doc) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : doc.entrySet()) {
      List<Object> vals = new ArrayList<>();
      flatten(e.getValue(), vals);
      for (Object v : vals) {
        if (v == null) continue;
        out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(render(v));
      }
    }
    return out;
  }

  // a repeated key's array is nested in Solr's record (SolrInputDocument flattens it)
  private static void flatten(Object v, List<Object> out) {
    if (v instanceof List<?> l) {
      for (Object o : l) flatten(o, out);
    } else {
      out.add(v);
    }
  }

  private static Map<String, List<String>> render(FastJsonDocsLoader.Parsed doc) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (int i = 0; i < doc.size; i++)
      out.computeIfAbsent(doc.keys[i], k -> new ArrayList<>()).add(render(doc.values[i]));
    return out;
  }

  private static String render(Object v) {
    if (v instanceof Number n)
      return n instanceof Double || n instanceof Float
          ? "d" + n.doubleValue()
          : "l" + n.longValue();
    return v.getClass().getSimpleName().startsWith("Bool") ? "b" + v : "s" + v.toString();
  }
}
