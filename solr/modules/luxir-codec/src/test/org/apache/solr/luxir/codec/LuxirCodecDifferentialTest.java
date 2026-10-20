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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.SolrIndexSearcher;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luxir.lucene.codec.LuxirTerms;

/**
 * Text fields are stored with {@code postingsFormat="Luxir"}. {@code /select} answers natively;
 * {@code /lucene} is stock Solr over the same index. Every request must produce the same response.
 */
public class LuxirCodecDifferentialTest extends SolrTestCaseJ4 {
  static final String[] WORDS = {
    "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "theta", "kappa", "lambda", "sigma",
    "omega", "running", "runner", "runs", "books", "booking", "quick", "brown", "fox", "jumps",
    "lazy", "dog", "cafe", "naive", "resume", "solar", "lunar", "orbit", "rocket", "engine"
  };
  static final int NUM_DOCS = 1500;

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", getFile("luxir-codec/solr"));
    Random r = new Random(42);
    for (int i = 0; i < NUM_DOCS; i++) {
      assertU(
          adoc(
              "id",
              id(i),
              "title",
              words(r, 2 + r.nextInt(4)),
              "body",
              words(r, 5 + r.nextInt(60)),
              "cat",
              "c" + r.nextInt(6),
              "year",
              String.valueOf(1980 + r.nextInt(45)),
              "note_t",
              r.nextInt(5) == 0 ? words(r, 3) : "plain"));
      if (i % 211 == 0) assertU(commit());
    }
    assertU(commit());
  }

  static String id(int i) {
    return String.format(Locale.ROOT, "doc%05d", i);
  }

  static String words(Random r, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(' ');
      sb.append(WORDS[(int) Math.min(WORDS.length - 1, Math.abs(r.nextGaussian()) * 8)]);
    }
    return sb.toString();
  }

  /** Runs the request on both handlers and asserts identical responses. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> same(String... params) throws Exception {
    List<String> p = new ArrayList<>(List.of(params));
    p.addAll(List.of("wt", "json", "debug", "true"));
    String[] base = p.toArray(String[]::new);
    String lux = h.query("/select", req(base));
    String luc = h.query("/lucene", req(base));
    Map<String, Object> a = (Map<String, Object>) Utils.fromJSONString(lux);
    Map<String, Object> b = (Map<String, Object>) Utils.fromJSONString(luc);
    Map<String, Object> debug = (Map<String, Object>) a.get("debug");
    for (Map<String, Object> m : List.of(a, b)) {
      m.remove("responseHeader");
      m.remove("debug");
      normalizeTies(m);
    }
    assertEquals("request " + List.of(params) + "\nnative: " + lux + "\nlucene: " + luc, b, a);
    return debug;
  }

  static void assertNative(Map<String, Object> debug) {
    assertTrue(
        "not native: " + (debug == null ? null : debug.get("luxirCodec")), nativePath(debug));
  }

  /** Whether /select answered natively. */
  @SuppressWarnings("unchecked")
  static boolean nativePath(Map<String, Object> debug) {
    // a NamedList renders as a flat [key, value, ...] array
    return debug != null
        && debug.get("luxirCodec") instanceof List<?> l
        && l.size() >= 2
        && "native".equals(l.get(1));
  }

  // Scores agree to float rounding (Luxir sums boolean clauses in its own order); round them to 6
  // significant digits, and order docs with equal rounded scores by id, so a 1-ulp difference
  // cannot reorder a tie.
  @SuppressWarnings("unchecked")
  static void normalizeTies(Map<String, Object> rsp) {
    if (!(rsp.get("response") instanceof Map<?, ?> r)) return;
    Map<String, Object> resp = (Map<String, Object>) r;
    if (resp.get("maxScore") instanceof Number n) resp.put("maxScore", round(n));
    List<Map<String, Object>> docs = (List<Map<String, Object>>) resp.get("docs");
    for (Map<String, Object> d : docs) {
      if (d.get("score") instanceof Number n) d.put("score", round(n));
    }
    if (docs.isEmpty() || !docs.get(0).containsKey("score")) return;
    docs.sort(
        Comparator.comparing((Map<String, Object> d) -> -((Number) d.get("score")).doubleValue())
            .thenComparing(d -> (String) d.get("id")));
  }

  static double round(Number n) {
    return Double.parseDouble(String.format(Locale.ROOT, "%.6g", n.doubleValue()));
  }

  @Test
  public void testFieldsAreStoredNatively() throws Exception {
    SolrQueryRequest req = req();
    try {
      SolrIndexSearcher s = req.getSearcher();
      for (LeafReaderContext ctx : s.getIndexReader().leaves()) {
        LeafReader segment = FilterLeafReader.unwrap(ctx.reader());
        assertTrue(segment.terms("body") instanceof LuxirTerms);
        assertTrue(segment.terms("text") instanceof LuxirTerms);
        assertFalse(
            "string fields keep Lucene's format", segment.terms("cat") instanceof LuxirTerms);
      }
    } finally {
      req.close();
    }
  }

  @Test
  public void testRelevanceQueries() throws Exception {
    String[] queries = {
      "body:fox",
      "body:runs",
      "fox dog",
      "+fox +dog",
      "fox -dog",
      "body:\"quick brown\"",
      "body:\"quick fox\"~3",
      "title:alpha^3 body:alpha",
      "text:(alpha beta gamma)",
      "(alpha beta gamma)",
      "+body:alpha +(beta OR gamma)",
      "note_t:plain",
      "body:nosuchword",
      "*:*"
    };
    int nativeCount = 0;
    for (String q : queries) {
      Map<String, Object> d = same("q", q, "df", "body", "fl", "id,score", "rows", "15");
      if (nativePath(d)) nativeCount++;
    }
    // everything but the sloppy phrase (Lucene's sloppy scoring) and *:* (Solr's cached live docs)
    assertEquals(queries.length - 2, nativeCount);
  }

  @Test
  public void testPagingFiltersFacetsAndRows0() throws Exception {
    assertNative(
        same("q", "alpha beta", "df", "body", "fl", "id,score", "start", "7", "rows", "9"));
    assertNative(same("q", "alpha", "df", "body", "fl", "id", "fq", "cat:c1"));
    assertNative(
        same(
            "q",
            "alpha",
            "df",
            "body",
            "fl",
            "id,score",
            "fq",
            "year:[1990 TO 2000]",
            "fq",
            "-cat:c2"));
    assertNative(
        same("q", "gamma", "df", "body", "fl", "id", "facet", "true", "facet.field", "cat"));
    assertNative(
        same(
            "q",
            "gamma",
            "df",
            "body",
            "rows",
            "0",
            "facet",
            "true",
            "facet.query",
            "year:[2000 TO *]"));
    assertNative(same("q", "{!lucene df=title}alpha", "fl", "id,title,score"));
    // prefix (constant score), alone and inside a boolean; fuzzy (Lucene's rewrite, run natively)
    assertNative(same("q", "body:alph*", "fl", "id,score"));
    assertNative(same("q", "+body:bet* body:fox", "fl", "id,score"));
    assertNative(same("q", "body:lamda~1", "fl", "id,score"));
  }

  @Test
  public void testMinExactCount() throws Exception {
    // below the threshold both report the exact count
    assertNative(same("q", "body:\"quick brown\"", "fl", "id,score", "minExactCount", "100000"));
    // above it, native reports a lower bound >= the threshold, with the same top documents
    SolrQueryRequest r =
        req(
            "q",
            "alpha beta gamma",
            "df",
            "body",
            "fl",
            "id,score",
            "minExactCount",
            "20",
            "rows",
            "5",
            "wt",
            "json");
    String json = h.query("/select", r);
    @SuppressWarnings("unchecked")
    Map<String, Object> rsp =
        (Map<String, Object>) ((Map<String, Object>) Utils.fromJSONString(json)).get("response");
    long numFound = ((Number) rsp.get("numFound")).longValue();
    assertEquals(Boolean.FALSE, rsp.get("numFoundExact"));
    assertTrue(json, numFound >= 20 && numFound < 1500);
  }

  @Test
  public void testWildcardsRunNatively() throws Exception {
    // a wildcard's expansion runs as one native term set
    assertTrue(nativePath(same("q", "body:al?ha", "fl", "id,score")));
    assertTrue(nativePath(same("q", "body:*eta OR body:g*m*", "fl", "id,score")));
  }

  @Test
  public void testOtherShapesFallBackAndStillMatch() throws Exception {
    assertFalse(
        nativePath(same("q", "alpha", "df", "body", "fl", "id", "sort", "year asc, id asc")));
    assertFalse(
        nativePath(same("q", "{!edismax qf='title body' tie=0.1}alpha beta", "fl", "id,score")));
    assertFalse(
        nativePath(same("q", "body:fox", "fl", "id,score", "group", "true", "group.field", "cat")));
  }

  @Test
  public void testSolrFeaturesTheEngineSwapWouldLose() throws Exception {
    // update log + real-time get, atomic update, delete by query, highlighting: all Solr/Lucene,
    // all working over Luxir postings.
    assertU(adoc("id", "rt1", "body", "zebra stripes", "cat", "c1"));
    assertJQ(req("qt", "/get", "id", "rt1"), "/doc/body=='zebra stripes'");
    assertU(commit());
    assertJQ(req("q", "body:zebra", "fl", "id"), "/response/numFound==1");
    assertU(adoc(sdoc("id", "rt1", "cat", map("set", "c3"))));
    assertU(commit());
    assertJQ(req("q", "body:zebra", "fl", "id,cat"), "/response/docs/[0]/cat=='c3'");
    assertJQ(
        req("q", "body:zebra", "fl", "id", "hl", "true", "hl.fl", "body"),
        "/highlighting/rt1/body/[0]=='<em>zebra</em> stripes'");
    assertU(delQ("body:zebra"));
    assertU(commit());
    assertJQ(req("q", "body:zebra", "fl", "id"), "/response/numFound==0");
    same("q", "alpha", "df", "body", "fl", "id,score");
  }
}
