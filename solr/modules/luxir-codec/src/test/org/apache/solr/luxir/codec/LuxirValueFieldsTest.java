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
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luxir.lucene.codec.LuxirTerms;
import org.luxir.lucene.document.LuxirValueField;

/**
 * Luxir value fields (strings and points indexed natively) are copied to stock reference fields of
 * the same Solr types: every query, facet, sort and returned value on a native field must match its
 * reference, through overwrites and deletes.
 */
public class LuxirValueFieldsTest extends SolrTestCaseJ4 {
  static final int NUM_DOCS = 1200;

  /** native field -> reference field */
  static final Map<String, String> PAIRS =
      Map.of(
          "kind_s", "kind_ref",
          "cats_ss", "cats_ref",
          "tag_s", "tag_ref",
          "code_s", "code_ref",
          "year_i", "year_ref",
          "rev_l", "rev_ref",
          "pop_f", "pop_ref",
          "q_ds", "q_ref",
          "n_is", "n_ref",
          "ts_dt", "ts_ref");

  @BeforeClass
  public static void beforeClass() throws Exception {
    init(true);
  }

  /** Indexes the documents; without an update log, JSON documents take the direct path. */
  static void init(boolean updateLog) throws Exception {
    init(updateLog, true);
  }

  /** As {@link #init(boolean)}, with or without the final deletes. */
  static void init(boolean updateLog, boolean deletes) throws Exception {
    System.setProperty("luxir.test.ulog", Boolean.toString(updateLog));
    initCore("solrconfig.xml", "schema.xml", getFile("luxir-values/solr"));
    Random r = new Random(11);
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < NUM_DOCS; i++) {
      // ids repeat: later documents overwrite earlier ones
      int id = r.nextInt(6) == 0 ? r.nextInt(i + 1) : i;
      List<String> f = doc(r, id);
      if (r.nextBoolean()) {
        assertU(adoc(f.toArray(String[]::new)));
      } else {
        // through the fast JSON loader: values arrive as UTF-8 bytes
        json.setLength(0);
        json.append("[{");
        for (int k = 0; k < f.size(); k += 2) {
          if (k > 0) json.append(',');
          json.append('"').append(f.get(k)).append("\":").append(jsonValue(f.get(k), f.get(k + 1)));
        }
        json.append("}]");
        post("/update", json.toString());
      }
      if (i % 197 == 0) assertU(commit());
    }
    if (deletes) {
      assertU(delQ("kind_s:k5"));
      assertU(delI(id(3)));
    }
    assertU(commit());
  }

  @AfterClass
  public static void afterClass() {
    System.clearProperty("luxir.test.ulog");
  }

  static String jsonValue(String field, String v) {
    boolean number = field.matches(".*_(i|l|f|ds|is)");
    return number ? v : "\"" + v + "\"";
  }

  static String id(int i) {
    return String.format(Locale.ROOT, "v%05d", i);
  }

  static List<String> doc(Random r, int i) {
    List<String> f = new ArrayList<>(List.of("id", id(i)));
    if (r.nextInt(6) > 0) f.addAll(List.of("kind_s", "k" + r.nextInt(8)));
    for (int c = r.nextInt(4); c > 0; c--) f.addAll(List.of("cats_ss", "Cät " + r.nextInt(40)));
    if (r.nextInt(3) == 0) f.addAll(List.of("tag_s", r.nextBoolean() ? "" : "t" + r.nextInt(9)));
    if (r.nextInt(3) > 0) f.addAll(List.of("code_s", "c" + r.nextInt(300)));
    if (r.nextInt(5) > 0)
      f.addAll(List.of("year_i", "" + (1990 + r.nextInt(35) - (r.nextInt(30) == 0 ? 3000 : 0))));
    if (r.nextInt(5) > 0)
      f.addAll(
          List.of(
              "rev_l",
              "" + (r.nextInt(9) == 0 ? r.nextLong() : 400_000_000_000L + r.nextInt(99999))));
    if (r.nextInt(5) > 0)
      f.addAll(List.of("pop_f", "" + (r.nextInt(40) == 0 ? -0.0f : (r.nextFloat() - 0.3f) * 100)));
    for (int c = r.nextInt(3); c > 0; c--) f.addAll(List.of("q_ds", "" + r.nextGaussian() * 10));
    for (int c = r.nextInt(3); c > 0; c--) f.addAll(List.of("n_is", "" + r.nextInt(20)));
    if (r.nextInt(4) > 0) {
      f.addAll(
          List.of(
              "ts_dt",
              String.format(
                  Locale.ROOT,
                  "20%02d-%02d-%02dT%02d:00:00Z",
                  r.nextInt(25),
                  1 + r.nextInt(12),
                  1 + r.nextInt(28),
                  r.nextInt(24))));
    }
    if (r.nextBoolean()) f.addAll(List.of("body", "some text " + r.nextInt(50)));
    return f;
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

  @SuppressWarnings("unchecked")
  static Map<String, Object> query(String handler, String... params) throws Exception {
    List<String> p = new ArrayList<>(List.of(params));
    p.addAll(List.of("wt", "json"));
    Map<String, Object> rsp =
        (Map<String, Object>) Utils.fromJSONString(h.query(handler, req(p.toArray(String[]::new))));
    rsp.remove("responseHeader");
    return rsp;
  }

  /** Replaces native field names by their references, so both requests render alike. */
  static String ref(String s) {
    for (Map.Entry<String, String> e : PAIRS.entrySet()) s = s.replace(e.getKey(), e.getValue());
    return s;
  }

  /** The request on native fields and on the reference fields give the same response. */
  static void same(String... params) throws Exception {
    String[] refParams = new String[params.length];
    for (int i = 0; i < params.length; i++) refParams[i] = ref(params[i]);
    for (String handler : new String[] {"/select", "/lucene"}) {
      // compared as maps: returned fields come in field-number order, which differs
      Object expected = Utils.fromJSONString(ref(Utils.toJSONString(query(handler, refParams))));
      Object actual = Utils.fromJSONString(ref(Utils.toJSONString(query(handler, params))));
      assertEquals(handler + " " + List.of(params), expected, actual);
    }
    // and Luxir's components answer as Solr's own (facets counted natively, queries run natively)
    assertEquals(
        "/select vs /lucene " + List.of(params),
        query("/lucene", params),
        query("/select", params));
  }

  @Test
  public void testIndexedByLuxir() throws Exception {
    SolrQueryRequest req = req();
    try {
      int segments = 0;
      for (LeafReaderContext ctx : req.getSearcher().getIndexReader().leaves()) {
        LeafReader segment = FilterLeafReader.unwrap(ctx.reader());
        segments++;
        assertTrue(segment.terms("kind_s") instanceof LuxirTerms);
        assertFalse(segment.terms("kind_ref") instanceof LuxirTerms);
        assertFalse(
            "the uniqueKey stays on Lucene's path", segment.terms("id") instanceof LuxirTerms);
        for (String f : new String[] {"kind_s", "year_i", "q_ds", "ts_dt"}) {
          FieldInfo fi = segment.getFieldInfos().fieldInfo(f);
          assertNotEquals(f, 0, LuxirValueField.kind(fi));
        }
        assertEquals(
            "doc-values-only fields take the standard path",
            0,
            LuxirValueField.kind(segment.getFieldInfos().fieldInfo("code_s")));
      }
      assertTrue(segments > 0);
    } finally {
      req.close();
    }
  }

  @Test
  public void testQueries() throws Exception {
    String[] queries = {
      "kind_s:k3",
      "kind_s:k5",
      "kind_s:*",
      "-kind_s:k1",
      "kind_s:[k1 TO k3]",
      "kind_s:k*",
      "cats_ss:\"Cät 7\"",
      "cats_ss:Cät*",
      "tag_s:t2",
      "tag_s:\"\"",
      "tag_s:*",
      "year_i:2005",
      "year_i:[2000 TO 2010]",
      "year_i:{* TO 1000}",
      "year_i:*",
      "rev_l:[400000000000 TO 400000050000]",
      "rev_l:{0 TO *]",
      "pop_f:[-10 TO 50.5]",
      "pop_f:0",
      "q_ds:[-5 TO 5]",
      "q_ds:*",
      "n_is:7",
      "n_is:(1 OR 2 OR 3)",
      "ts_dt:[2010-01-01T00:00:00Z TO 2015-06-01T00:00:00Z]",
      "ts_dt:[NOW-30YEARS TO *]",
      "+kind_s:k2 +year_i:[2000 TO *]",
      "body:text kind_s:k2"
    };
    for (String q : queries) {
      same("q", q, "fl", "id", "sort", "id asc", "rows", "2000");
      same("q", "*:*", "fq", q, "fl", "id", "sort", "id asc", "rows", "2000");
    }
  }

  @Test
  public void testFacetsSortsAndValues() throws Exception {
    for (String method : new String[] {"fc", "enum", "fcs"}) {
      same(
          "q",
          "*:*",
          "rows",
          "0",
          "facet",
          "true",
          "facet.field",
          "kind_s",
          "facet.field",
          "cats_ss",
          "facet.field",
          "year_i",
          "facet.missing",
          "true",
          "facet.method",
          method,
          "facet.limit",
          "-1",
          "facet.mincount",
          "1");
    }
    // count order and paging: the top terms only (with and without zero counts, an offset), on
    // all docs and on a filtered subset
    for (String fq : new String[] {"*:*", "kind_s:k1 OR year_i:[2000 TO *]"}) {
      for (String[] page :
          new String[][] {
            {"facet.limit", "3"},
            {"facet.limit", "5", "facet.mincount", "1"},
            {"facet.limit", "4", "facet.offset", "2"},
            {"facet.limit", "2", "facet.mincount", "2", "facet.sort", "index"},
            {"facet.limit", "100", "facet.mincount", "0"}
          }) {
        List<String> p =
            new ArrayList<>(
                List.of(
                    "q",
                    "*:*",
                    "fq",
                    fq,
                    "rows",
                    "0",
                    "facet",
                    "true",
                    "facet.field",
                    "kind_s",
                    "facet.field",
                    "cats_ss",
                    "facet.field",
                    "year_i",
                    "facet.missing",
                    "true"));
        p.addAll(List.of(page));
        same(p.toArray(String[]::new));
      }
    }
    // no doc values: by terms and postings
    same(
        "q",
        "*:*",
        "rows",
        "0",
        "facet",
        "true",
        "facet.field",
        "tag_s",
        "facet.method",
        "enum",
        "facet.missing",
        "true");
    same(
        "q",
        "*:*",
        "rows",
        "0",
        "facet",
        "true",
        "facet.range",
        "year_i",
        "facet.range.start",
        "1990",
        "facet.range.end",
        "2030",
        "facet.range.gap",
        "5");
    same(
        "q",
        "*:*",
        "rows",
        "0",
        "json.facet",
        "{k:{type:terms,field:kind_s,limit:-1,facet:{p:\"avg(pop_f)\",y:\"max(year_i)\",q:\"sum(q_ds)\"}},"
            + "c:{type:terms,field:cats_ss,limit:5},r:{type:range,field:rev_l,start:400000000000,"
            + "end:400000100000,gap:20000},t:\"min(ts_dt)\",u:\"unique(code_s)\"}");
    same(
        "q",
        "*:*",
        "rows",
        "0",
        "stats",
        "true",
        "stats.field",
        "pop_f",
        "stats.field",
        "year_i",
        "stats.field",
        "ts_dt",
        "stats.field",
        "kind_s");
    for (String sort :
        new String[] {
          "year_i asc",
          "pop_f desc",
          "kind_s asc",
          "q_ds asc",
          "q_ds desc",
          "ts_dt desc",
          "rev_l asc",
          "n_is asc",
          "code_s desc",
          "field(q_ds,max) desc"
        }) {
      same("q", "*:*", "fl", "id", "sort", sort + ",id asc", "rows", "300");
    }
    // all docs (or a filter's) by one field, ties included: Luxir's top-k answers natively
    for (String sort : new String[] {"year_i asc", "year_i desc", "rev_l desc", "ts_dt asc"}) {
      same("q", "*:*", "fl", "id", "sort", sort, "rows", "50");
      same("q", "*:*", "fq", "kind_s:k1", "fl", "id", "sort", sort, "rows", "50");
      Map<?, ?> debug =
          (Map<?, ?>) query("/select", "q", "*:*", "sort", sort, "debug", "true").get("debug");
      assertEquals(sort, List.of("path", "native"), debug.get("luxirCodec"));
    }
    // values: stored, and doc values as stored
    same(
        "q",
        "*:*",
        "fl",
        "id,kind_s,cats_ss,tag_s,code_s,year_i,rev_l,pop_f,q_ds,n_is,ts_dt",
        "sort",
        "id asc",
        "rows",
        "2000");
    same(
        "q",
        "year_i:[2000 TO *]",
        "fl",
        "id,score,pop_f",
        "sort",
        "pop_f desc,id asc",
        "rows",
        "50");
    // function queries over doc values
    same(
        "q",
        "{!func}sum(year_i,pop_f)",
        "fl",
        "id,score",
        "sort",
        "score desc,id asc",
        "rows",
        "50");
  }

  @Test
  public void testRealTimeGetAndUpdates() throws Exception {
    assumeTrue("needs the update log", h.getCore().getUpdateHandler().getUpdateLog() != null);
    assertU(
        adoc(
            "id", id(5000), "kind_s", "k9", "year_i", "2099", "tag_s", "rt", "n_is", "4", "n_is",
            "1"));
    // from the update log, then from the index
    for (int pass = 0; pass < 2; pass++) {
      Map<String, Object> got = query("/get", "id", id(5000), "fl", "id,kind_s,year_i,tag_s,n_is");
      Map<?, ?> doc = (Map<?, ?>) got.get("doc");
      assertEquals("k9", doc.get("kind_s"));
      assertEquals(2099L, ((Number) doc.get("year_i")).longValue());
      assertEquals("rt", doc.get("tag_s"));
      assertU(commit());
    }
    same("q", "kind_s:k9", "fl", "id,year_i,n_is", "sort", "id asc");
    assertU(delQ("year_i:2099"));
    assertU(commit());
    assertEquals(
        0L, ((Map<?, ?>) query("/lucene", "q", "kind_s:k9").get("response")).get("numFound"));
  }

  @Test
  public void testTooLongStringRejected() {
    String id = id(7000);
    assertFailedU(adoc("id", id, "kind_s", "é".repeat(200)));
    assertU(commit());
    assertQ(req("q", "id:" + id), "//result[@numFound='0']");
  }
}
