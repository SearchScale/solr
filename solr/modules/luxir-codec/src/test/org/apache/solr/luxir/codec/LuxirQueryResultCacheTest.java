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
import java.util.Map;
import java.util.Random;
import org.apache.lucene.search.Query;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.DocSetUtil;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryUtils;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.WrappedQuery;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The native path reads and fills Solr's query result and filter caches as SolrIndexSearcher does.
 */
public class LuxirQueryResultCacheTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    System.setProperty("luxir.test.queryResultCacheSize", "64");
    System.setProperty("luxir.test.queryResultWindowSize", "20");
    System.setProperty("luxir.test.filterCache", "true");
    initCore("solrconfig.xml", "schema.xml", getFile("luxir-codec/solr"));
    Random r = new Random(7);
    for (int i = 0; i < 400; i++) {
      assertU(
          adoc(
              "id",
              LuxirCodecDifferentialTest.id(i),
              "body",
              LuxirCodecDifferentialTest.words(r, 5 + r.nextInt(30)),
              "cat",
              "c" + r.nextInt(4)));
      if (i % 150 == 0) assertU(commit());
    }
    assertU(commit());
  }

  @AfterClass
  public static void afterClass() {
    System.clearProperty("luxir.test.queryResultCacheSize");
    System.clearProperty("luxir.test.queryResultWindowSize");
    System.clearProperty("luxir.test.filterCache");
  }

  @Before
  public void clearCache() throws Exception {
    h.getCore()
        .withSearcher(
            s -> {
              s.getQueryResultCache().clear();
              s.getFilterCache().clear();
              return null;
            });
  }

  /** The response of /select (debug path stripped) and the path that answered it. */
  @SuppressWarnings("unchecked")
  private static String select(String handler, String... params) throws Exception {
    List<String> p = new ArrayList<>(List.of(params));
    p.addAll(List.of("wt", "json", "debug", "true"));
    Map<String, Object> rsp =
        (Map<String, Object>) Utils.fromJSONString(h.query(handler, req(p.toArray(String[]::new))));
    Map<String, Object> debug = (Map<String, Object>) rsp.get("debug");
    String path =
        debug != null && debug.get("luxirCodec") instanceof List<?> l && l.size() >= 2
            ? (String) l.get(1)
            : "lucene";
    rsp.remove("responseHeader");
    rsp.remove("debug");
    LuxirCodecDifferentialTest.normalizeTies(rsp);
    return path + " " + Utils.toJSONString(rsp);
  }

  private static void assertPath(String path, String answer) {
    assertTrue(answer, answer.startsWith(path + " "));
  }

  private static String body(String answer) {
    return answer.substring(answer.indexOf(' ') + 1);
  }

  @Test
  public void testRepeatedRequestsComeFromTheCache() throws Exception {
    String[] q = {"q", "alpha beta", "df", "body", "fl", "id,score", "rows", "5"};
    String first = select("/select", q);
    assertPath("native", first);
    String again = select("/select", q);
    assertPath("cache", again);
    assertEquals(body(first), body(again));

    // another page inside the cached window of 20
    String[] page = {"q", "alpha beta", "df", "body", "fl", "id,score", "start", "10", "rows", "8"};
    String cached = select("/select", page);
    assertPath("cache", cached);
    assertEquals(body(select("/lucene", page)), body(cached));

    // beyond it: searched again
    String[] deep = {
      "q", "alpha beta", "df", "body", "fl", "id,score", "start", "15", "rows", "10"
    };
    String searched = select("/select", deep);
    assertPath("native", searched);
    assertEquals(body(select("/lucene", deep)), body(searched));
  }

  @Test
  public void testEntriesAreSharedWithSolrsPath() throws Exception {
    // filters are part of the key
    String[] q = {"q", "gamma", "df", "body", "fl", "id", "fq", "cat:c1"};
    String stock = select("/lucene", q);
    String cached = select("/select", q);
    assertPath("cache", cached);
    assertEquals(body(stock), body(cached));
    assertPath("native", select("/select", "q", "gamma", "df", "body", "fl", "id", "fq", "cat:c2"));

    // the native path stores under the key Solr's path uses: no second entry for the same request
    String[] repeated = {"q", "delta lambda", "df", "body", "fl", "id,score", "rows", "3"};
    String nat = select("/select", repeated);
    assertPath("native", nat);
    int entries = cacheSize();
    assertEquals(body(nat), body(select("/lucene", repeated)));
    assertEquals(entries, cacheSize());
  }

  @Test
  public void testUncachedRequestsSearch() throws Exception {
    // a query marked cache=false is neither looked up nor stored
    String[] nc = {"q", "{!cache=false df=body}alpha", "fl", "id"};
    assertPath("native", select("/select", nc));
    assertPath("native", select("/select", nc));
    // faceting needs the doc set: searched natively, as Solr would compute it too
    String[] facet = {"q", "beta", "df", "body", "fl", "id", "facet", "true", "facet.field", "cat"};
    assertPath("native", select("/select", facet));
    String again = select("/select", facet);
    assertPath("native", again);
    assertEquals(body(select("/lucene", facet)), body(again));
  }

  @Test
  public void testFiltersFillTheFilterCache() throws Exception {
    for (String fq : new String[] {"cat:c1", "-cat:c2", "body:alpha", "{!terms f=cat}c0,c3"}) {
      String[] q = {"q", "gamma", "df", "body", "fl", "id", "fq", fq};
      String answer = select("/select", q);
      assertPath("native", answer);
      assertEquals(body(select("/lucene", q)), body(answer));
      // cached under Solr's key (the positive query), as Solr would compute it
      h.getCore()
          .withSearcher(
              s -> {
                try (SolrQueryRequest r = req()) {
                  Query key = QueryUtils.getAbs(QParser.getParser(fq, r).getQuery());
                  DocSet cached = s.getFilterCache().get(key);
                  assertNotNull(fq, cached);
                  WrappedQuery uncached = new WrappedQuery(key);
                  uncached.setCache(false);
                  assertTrue(fq, DocSetUtil.equals(s.getDocSet(uncached), cached));
                } catch (SyntaxError e) {
                  throw new RuntimeException(e);
                }
                return null;
              });
      // another query reuses it
      String[] other = {"q", "delta", "df", "body", "fl", "id", "fq", fq};
      assertEquals(body(select("/lucene", other)), body(select("/select", other)));
    }
    // a filter marked cache=false is not stored
    int entries = h.getCore().withSearcher(s -> s.getFilterCache().size());
    String[] nc = {"q", "gamma", "df", "body", "fl", "id", "fq", "{!cache=false}cat:c3"};
    assertPath("native", select("/select", nc));
    assertEquals(body(select("/lucene", nc)), body(select("/select", nc)));
    assertEquals(entries, (int) h.getCore().withSearcher(s -> s.getFilterCache().size()));
  }

  private static int cacheSize() throws Exception {
    return h.getCore().withSearcher(s -> s.getQueryResultCache().size());
  }
}
