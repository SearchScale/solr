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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.util.Utils;
import org.junit.Test;

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

  private static String str(Random r) {
    StringBuilder sb = new StringBuilder("\"");
    int n = r.nextInt(r.nextInt(5) == 0 ? 200 : 12);
    for (int i = 0; i < n; i++) sb.append(PIECES[r.nextInt(PIECES.length)]);
    return sb.append('"').toString();
  }

  private static String value(Random r) {
    if (r.nextInt(40) == 0) return "12345678901234567890"; // beyond a long: left to Solr's loader
    return switch (r.nextInt(9)) {
      case 0 -> Long.toString(r.nextLong());
      case 1 -> Integer.toString(r.nextInt(1000) - 500);
      case 2 -> Double.toString(r.nextGaussian() * 1e6);
      case 3 -> r.nextBoolean() ? "true" : "false";
      case 4 -> "null";
      case 5 -> "-0.5e-3";
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

  /** Solr's parse with nulls dropped and values as strings, arrays flattened (repeated keys). */
  private static Map<String, List<String>> normalize(Map<String, Object> doc) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : doc.entrySet()) {
      List<Object> vals =
          e.getValue() instanceof List<?> l
              ? new ArrayList<>(l)
              : new ArrayList<>(Arrays.asList(e.getValue()));
      for (Object v : vals) {
        if (v == null) continue;
        out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(render(v));
      }
    }
    return out;
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
