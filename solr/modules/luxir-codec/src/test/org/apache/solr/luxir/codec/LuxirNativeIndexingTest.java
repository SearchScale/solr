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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luxir.lucene.codec.LuxirTerms;

/**
 * Fields of type LuxirTextField are analyzed and inverted by Luxir; each is copied to a _ref field
 * with the same analysis chain inverted by Lucene. Every query on the Luxir field must return what
 * the same query on the reference field returns, and Solr features keep working.
 */
public class LuxirNativeIndexingTest extends SolrTestCaseJ4 {
  static final String[] WORDS = {
    "Running", "runner", "runs", "BOOKS", "booking", "café", "naïve", "résumé", "quick", "brown",
    "fox", "dog's", "lazy", "U.S.A", "don't", "x-ray", "alpha", "beta", "gamma", "delta"
  };
  static final int NUM_DOCS = 1200;

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", getFile("luxir-native/solr"));
    Random r = new Random(7);
    for (int i = 0; i < NUM_DOCS; i++) {
      List<String> f =
          new ArrayList<>(
              List.of(
                  "id",
                  id(i),
                  "title_lx",
                  words(r, 2 + r.nextInt(4)),
                  "body_lx",
                  words(r, 5 + r.nextInt(60)),
                  "cat",
                  "c" + r.nextInt(5),
                  "year",
                  "" + (1990 + r.nextInt(30))));
      if (r.nextInt(8) == 0) f.addAll(List.of("body_lx", words(r, 4))); // multi-valued
      if (r.nextInt(5) == 0) f.addAll(List.of("note_lx", words(r, 3)));
      assertU(adoc(f.toArray(String[]::new)));
      if (i % 173 == 0) assertU(commit());
    }
    assertU(commit());
  }

  static String id(int i) {
    return String.format(Locale.ROOT, "d%05d", i);
  }

  static String words(Random r, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(r.nextInt(6) == 0 ? ", " : " ");
      sb.append(WORDS[(int) Math.min(WORDS.length - 1, Math.abs(r.nextGaussian()) * 6)]);
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> select(String... params) throws Exception {
    List<String> p = new ArrayList<>(List.of(params));
    p.addAll(List.of("wt", "json", "fl", "id,score", "rows", "20"));
    Map<String, Object> rsp =
        (Map<String, Object>)
            Utils.fromJSONString(h.query("/select", req(p.toArray(String[]::new))));
    Map<String, Object> response = (Map<String, Object>) rsp.get("response");
    List<Map<String, Object>> docs = (List<Map<String, Object>>) response.get("docs");
    return response;
  }

  /** The same query on the Luxir-indexed fields and on their Lucene-indexed references. */
  static void same(String q, String... params) throws Exception {
    List<String> lx = new ArrayList<>(List.of("q", q));
    List<String> ref = new ArrayList<>(List.of("q", q.replace("_lx", "_ref")));
    lx.addAll(List.of(params));
    ref.addAll(List.of(params));
    Map<String, Object> expected = select(ref.toArray(String[]::new));
    Map<String, Object> actual = select(lx.toArray(String[]::new));
    assertEquals(q, expected.get("numFound"), actual.get("numFound"));
    assertScores(q, (Number) expected.get("maxScore"), (Number) actual.get("maxScore"));
    List<Map<String, Object>> e = docs(expected), a = docs(actual);
    assertEquals(q, e.size(), a.size());
    for (int i = 0; i < e.size(); i++) {
      // same documents in the same order; scores to float rounding (boolean sums may differ by an
      // ulp)
      assertScores(
          q + " rank " + i, (Number) e.get(i).get("score"), (Number) a.get(i).get("score"));
      if (!e.get(i).get("id").equals(a.get(i).get("id"))) {
        double se = ((Number) e.get(i).get("score")).doubleValue();
        boolean tie =
            (i > 0 && close(se, ((Number) e.get(i - 1).get("score")).doubleValue()))
                || (i + 1 < e.size()
                    && close(se, ((Number) e.get(i + 1).get("score")).doubleValue()));
        assertTrue(q + " rank " + i + ": " + a.get(i) + " vs " + e.get(i), tie);
      }
    }
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> docs(Map<String, Object> response) {
    return (List<Map<String, Object>>) response.get("docs");
  }

  static boolean close(double a, double b) {
    return Math.abs(a - b) <= 1e-5 * Math.max(Math.abs(a), Math.abs(b));
  }

  static void assertScores(String info, Number e, Number a) {
    if (e == null || a == null) {
      assertEquals(info, e, a);
      return;
    }
    assertTrue(info + ": " + e + " vs " + a, close(e.doubleValue(), a.doubleValue()));
  }

  @Test
  public void testIndexedByLuxir() throws Exception {
    SolrQueryRequest req = req();
    try {
      for (LeafReaderContext ctx : req.getSearcher().getIndexReader().leaves()) {
        LeafReader segment = FilterLeafReader.unwrap(ctx.reader());
        for (String field : List.of("body_lx", "_text_lx")) {
          assertTrue(field, segment.terms(field) instanceof LuxirTerms);
          assertNotNull(field, segment.getNormValues(field));
        }
      }
    } finally {
      req.close();
    }
  }

  @Test
  public void testSameResultsAsLuceneInversion() throws Exception {
    String[] queries = {
      "body_lx:fox",
      "body_lx:runs",
      "body_lx:Running",
      "body_lx:dog's",
      "body_lx:CAFE",
      "body_lx:naive",
      "body_lx:(quick brown)",
      "+body_lx:quick +body_lx:fox",
      "body_lx:\"quick brown\"",
      "body_lx:\"lazy dog\"~2",
      "title_lx:alpha^2 body_lx:alpha",
      "body_lx:run*",
      "body_lx:boo*",
      "body_lx:rumner~1",
      // wildcards: the whole pattern is one multi-term token
      "body_lx:r*s",
      "body_lx:qu?ck",
      "body_lx:*own",
      "note_lx:gamma",
      "body_lx:u.s.a",
      "body_lx:x-ray",
      // copied from title_lx and body_lx into a field named by its native schema name
      "_text_lx:fox",
      "_text_lx:(alpha runs)",
      "_text_lx:\"quick brown\"",
      "_text_lx:run*"
    };
    for (String q : queries) {
      same(q);
      same(q, "fq", "cat:c1");
      same(q, "facet", "true", "facet.field", "cat");
    }
    // multi-term text is folded by the Luxir chain (the class-analyzer reference field does not)
    assertEquals(
        select("q", "body_ref:boo*").get("numFound"), select("q", "body_lx:BOO*").get("numFound"));
  }

  /** Sends a JSON body to a named update handler. */
  static void post(String handler, String json, String... params) throws Exception {
    SolrQueryRequest req = req(params);
    try {
      ContentStreamBase.StringStream body =
          new ContentStreamBase.StringStream(json, "application/json");
      ((org.apache.solr.request.SolrQueryRequestBase) req).setContentStreams(List.of(body));
      SolrQueryResponse rsp = new SolrQueryResponse();
      h.getCore().execute(h.getCore().getRequestHandler(handler), req, rsp);
      if (rsp.getException() != null) throw rsp.getException();
    } finally {
      req.close();
    }
  }

  @Test
  public void testFastJsonLoaderMatchesStandardLoader() throws Exception {
    // the same JSON through the fast loader (/update) and Solr's own (/update-standard)
    String docs =
        "[{\"id\":\"j1\",\"body_lx\":\"Tab\\there \\\"quoted\\\" back\\\\slash \\u00e9t\\u00e9 "
            + "emoji \\ud83d\\ude00 snowman \\u2603\",\"title_lx\":\"caf\\u00e9 au lait\",\"year\":2001,\"cat\":\"c1\"},"
            + "{\"id\":\"j2\",\"body_lx\":[\"first value\",\"second value\"],\"note_lx\":\"plain words\",\"year\":1999,\"cat\":null}]";
    String other = docs.replace("\"j1\"", "\"s1\"").replace("\"j2\"", "\"s2\"");
    assertNotNull(
        "parser accepts: " + docs,
        new FastJsonDocsLoader.Parser(docs.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .docs());
    long fastBefore = FastJsonDocsLoader.FAST_REQUESTS.sum();
    post("/update", docs);
    assertEquals("fast path used", fastBefore + 1, FastJsonDocsLoader.FAST_REQUESTS.sum());
    post("/update-standard", other);
    assertU(commit());
    for (String[] pair : new String[][] {{"j1", "s1"}, {"j2", "s2"}}) {
      String fast =
          h.query(
              req(
                  "q",
                  "id:" + pair[0],
                  "fl",
                  "body_lx,title_lx,note_lx,year,cat",
                  "wt",
                  "json",
                  "omitHeader",
                  "true"));
      String std =
          h.query(
              req(
                  "q",
                  "id:" + pair[1],
                  "fl",
                  "body_lx,title_lx,note_lx,year,cat",
                  "wt",
                  "json",
                  "omitHeader",
                  "true"));
      assertEquals(std, fast);
    }
    assertJQ(
        req("q", "body_lx:snowman AND body_lx:quoted", "fl", "id", "sort", "id asc"),
        "/response/numFound==2");
    assertJQ(
        req("q", "body_lx:\"second value\"", "fl", "id", "sort", "id asc"),
        "/response/numFound==2");
    // shapes the fast loader leaves to Solr's: lenient JSON, commands, atomic updates
    post("/update", json("[{'id':'j4','body_lx':'single quoted'}]"), "commit", "true");
    assertJQ(req("q", "body_lx:quoted AND id:j4", "fl", "id"), "/response/numFound==1");
    post("/update", json("{'add':{'doc':{'id':'j3','body_lx':'command form'}},'commit':{}}"));
    assertJQ(req("q", "body_lx:command", "fl", "id"), "/response/numFound==1");
    post("/update", json("[{'id':'j3','cat':{'set':'c4'}}]"), "commit", "true");
    assertJQ(
        req("q", "id:j3", "fl", "id,cat,body_lx"),
        "/response/docs/[0]/cat=='c4'",
        "/response/docs/[0]/body_lx==['command form']");
    assertU(delQ("id:(j1 j2 j3 j4 s1 s2)"));
    assertU(commit());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSolrFeatures() throws Exception {
    // stored text, highlighting, atomic update (rebuilt from stored values), real-time get, DBQ
    assertU(adoc("id", "x1", "body_lx", "The Zebra stripes are running", "cat", "c1"));
    assertJQ(req("qt", "/get", "id", "x1"), "/doc/body_lx==['The Zebra stripes are running']");
    assertU(commit());
    assertJQ(
        req("q", "body_lx:zebra", "fl", "id,body_lx"),
        "/response/docs/[0]/body_lx==['The Zebra stripes are running']");
    assertJQ(
        req("q", "body_lx:zebras", "fl", "id", "hl", "true", "hl.fl", "body_lx"),
        "/highlighting/x1/body_lx/[0]=='The <em>Zebra</em> stripes are running'");
    assertU(adoc(sdoc("id", "x1", "cat", map("set", "c4"))));
    assertU(commit());
    assertJQ(req("q", "body_lx:stripe AND cat:c4", "fl", "id"), "/response/numFound==1");
    assertJQ(req("q", "body_ref:stripe AND cat:c4", "fl", "id"), "/response/numFound==1");
    assertU(delQ("body_lx:zebra"));
    assertU(commit());
    assertJQ(req("q", "body_lx:zebra", "fl", "id"), "/response/numFound==0");
    same("body_lx:fox");
  }
}
