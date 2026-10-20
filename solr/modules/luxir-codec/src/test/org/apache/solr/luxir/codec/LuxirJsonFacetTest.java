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

import static org.apache.solr.luxir.codec.LuxirValueFieldsTest.same;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * JSON facets ({@code json.facet}) collected by Luxir answer exactly as Solr's own: on native
 * fields and on their stock reference fields, through Luxir's facet module and Solr's.
 */
public class LuxirJsonFacetTest extends SolrTestCaseJ4 {

  private static final String[] QUERIES = {
    "*:*", "kind_s:k3", "body:text", "year_i:[2000 TO 2010]", "cats_ss:\"Cät 7\" OR pop_f:[0 TO *]"
  };

  @BeforeClass
  public static void beforeClass() throws Exception {
    LuxirValueFieldsTest.init(true);
  }

  @AfterClass
  public static void afterClass() {
    LuxirValueFieldsTest.afterClass();
  }

  /** The facet request under each query (and with a filter), as Solr's own would answer it. */
  private static void facet(String json) throws Exception {
    for (String q : QUERIES) {
      same("q", q, "rows", "0", "json.facet", json);
    }
    same("q", "*:*", "fq", "year_i:[1995 TO *]", "rows", "0", "json.facet", json);
  }

  private static final String STATS =
      "s:'sum(pop_f)',c:'countvals(year_i)',m:'missing(rev_l)',v:'variance(pop_f)',"
          + "d:'stddev(year_i)',q:'sumsq(rev_l)',mi:'min(pop_f)',ma:'max(year_i)',"
          + "mt:'min(ts_dt)',xt:'max(ts_dt)',av:'avg(rev_l)',ml:'min(rev_l)',xd:'max(pop_f)'";

  @Test
  public void testTermsFacets() throws Exception {
    long ords = accelerated(LuxirFacetAccelerator.ORDS);
    long slots = accelerated(LuxirFacetAccelerator.SLOT);
    facet("{k:{type:terms,field:kind_s}}");
    facet("{k:{type:terms,field:cats_ss,limit:-1,mincount:0}}");
    facet("{k:{type:terms,field:code_s,limit:7,offset:3,sort:'index asc'}}");
    facet("{k:{type:terms,field:code_s,prefix:c1,limit:10,sort:'index desc'}}");
    facet("{k:{type:terms,field:kind_s,missing:true,numBuckets:true}}");
    // stats collected with the buckets (sorted by one) and after them (sorted by count)
    facet("{k:{type:terms,field:kind_s,sort:'s desc',facet:{" + STATS + "}}}");
    facet("{k:{type:terms,field:code_s,limit:5,sort:'mi asc',facet:{" + STATS + "}}}");
    facet("{k:{type:terms,field:kind_s,facet:{" + STATS + "}}}");
    facet("{k:{type:terms,field:cats_ss,limit:5,sort:'av desc',facet:{" + STATS + "}}}");
    facet(
        "{k:{type:terms,field:code_s,limit:4,prelim_sort:'count desc',sort:'s asc',facet:{s:'sum(year_i)'}}}");
    // sub-facets and the all/missing buckets
    facet(
        "{k:{type:terms,field:kind_s,limit:3,allBuckets:true,missing:true,facet:{"
            + "s:'sum(year_i)',c:{type:terms,field:cats_ss,limit:3,facet:{x:'max(pop_f)'}}}}}");
    assertTrue(accelerated(LuxirFacetAccelerator.ORDS) > ords);
    assertTrue(accelerated(LuxirFacetAccelerator.SLOT) > slots);
  }

  @Test
  public void testRangeFacets() throws Exception {
    long ranges = accelerated(LuxirFacetAccelerator.RANGES);
    facet(
        "{r:{type:range,field:year_i,start:1990,end:2030,gap:5,other:all,facet:{" + STATS + "}}}");
    facet("{r:{type:range,field:year_i,start:1990,end:2027,gap:4,hardend:true,mincount:1}}");
    facet(
        "{r:{type:range,field:pop_f,start:-30,end:70,gap:7.5,include:[lower,upper,edge],"
            + "other:all,facet:{s:'sum(year_i)'}}}");
    facet("{r:{type:range,field:pop_f,start:-0.0,end:10,gap:0.5,include:upper}}");
    facet(
        "{r:{type:range,field:ts_dt,start:'2000-01-01T00:00:00Z',end:'2025-01-01T00:00:00Z',"
            + "gap:'+3YEARS',other:[before,after],hardend:true,facet:{a:'avg(year_i)'}}}");
    facet("{r:{type:range,field:rev_l,start:400000000000,end:400000100000,gap:10000,other:all}}");
    facet(
        "{r:{type:range,field:year_i,ranges:[{from:1990,to:2000},{from:1995,to:2010,"
            + "inclusive_to:true},{range:'[2000,*)'},{range:'(*,1995]'}],facet:{m:'min(pop_f)'}}}");
    facet("{r:{type:range,field:n_is,start:0,end:20,gap:3,other:all}}");
    facet("{r:{type:range,field:q_ds,start:-20,end:20,gap:2.5,other:between}}");
    // ranges with sub-facets keep Solr's per-range domains
    facet(
        "{r:{type:range,field:year_i,start:1990,end:2030,gap:10,facet:{k:{type:terms,field:kind_s}}}}");
    assertTrue(accelerated(LuxirFacetAccelerator.RANGES) > ranges);
  }

  @Test
  public void testNumericTermsAndStats() throws Exception {
    long values = accelerated(LuxirFacetAccelerator.VALUES);
    long slots = accelerated(LuxirFacetAccelerator.SLOT);
    facet("{y:{type:terms,field:year_i,limit:10}}");
    facet("{y:{type:terms,field:n_is,limit:-1}}");
    facet("{y:{type:terms,field:pop_f,limit:5,sort:'index desc'}}");
    facet("{y:{type:terms,field:ts_dt,limit:5,mincount:2}}");
    facet("{y:{type:terms,field:rev_l,limit:5,numBuckets:true}}");
    facet("{y:{type:terms,field:q_ds,limit:6,sort:'index asc'}}");
    facet("{y:{type:terms,field:year_i,limit:5,facet:{s:'sum(pop_f)'}}}");
    // a whole domain's stats, and a query bucket's
    facet("{" + STATS + "}");
    facet("{x:{type:query,q:'kind_s:k1',facet:{" + STATS + "}}}");
    facet("{x:{type:query,q:'year_i:[2030 TO *]',facet:{s:'sum(pop_f)',m:'min(year_i)'}}}");
    assertTrue(accelerated(LuxirFacetAccelerator.VALUES) > values);
    assertTrue(accelerated(LuxirFacetAccelerator.SLOT) > slots);
  }

  @Test
  public void testNumericTermsListed() throws Exception {
    // values judged mostly distinct after one doc: listed in doc order, repeats merged by Solr
    long values = accelerated(LuxirFacetAccelerator.VALUES);
    int sample = LuxirFacetAccelerator.nearUniqueSample;
    LuxirFacetAccelerator.nearUniqueSample = 1;
    try {
      facet("{y:{type:terms,field:year_i,limit:10}}");
      facet("{y:{type:terms,field:n_is,limit:-1}}");
      facet("{y:{type:terms,field:pop_f,limit:5,sort:'index desc'}}");
      facet("{y:{type:terms,field:rev_l,limit:5,numBuckets:true,mincount:2}}");
      for (String q : QUERIES) {
        same(
            "q", q,
            "rows", "0",
            "facet", "true",
            "facet.field", "year_i",
            "facet.field", "pop_f",
            "facet.limit", "-1",
            "facet.mincount", "1",
            "facet.missing", "true");
      }
    } finally {
      LuxirFacetAccelerator.nearUniqueSample = sample;
    }
    assertTrue(accelerated(LuxirFacetAccelerator.VALUES) > values);
  }

  @Test
  public void testOthersTakeSolrsPath() throws Exception {
    // functions, multi-valued and string aggregations, unique/hll/percentile, hashing, relatedness
    facet("{s:'sum(mul(year_i,2))',u:'unique(kind_s)',h:'hll(code_s)',p:'percentile(pop_f,50)'}");
    facet("{k:{type:terms,field:kind_s,facet:{s:'sum(n_is)',m:'min(code_s)',a:'avg(q_ds)'}}}");
    facet("{k:{type:terms,field:kind_s,method:dvhash}}");
    facet("{k:{type:terms,field:tag_s,limit:5}}");
    for (String q : QUERIES) {
      same(
          "q", q,
          "rows", "0",
          "fore", "year_i:[2000 TO *]",
          "back", "*:*",
          "json.facet", "{k:{type:terms,field:kind_s,facet:{r:'relatedness($fore,$back)'}}}");
    }
  }

  @Test
  public void testRandomFacets() throws Exception {
    Random r = new Random(random().nextLong());
    String[] terms = {"kind_s", "cats_ss", "code_s", "year_i", "n_is", "pop_f", "ts_dt"};
    String[] aggs = {
      "sum(pop_f)",
      "avg(year_i)",
      "min(rev_l)",
      "max(ts_dt)",
      "sumsq(year_i)",
      "countvals(pop_f)",
      "missing(ts_dt)",
      "variance(rev_l)",
      "stddev(pop_f)",
      "min(pop_f)",
      "max(rev_l)"
    };
    for (int iter = 0; iter < 40; iter++) {
      List<String> parts = new ArrayList<>();
      String field = terms[r.nextInt(terms.length)];
      parts.add("type:terms");
      parts.add("field:" + field);
      parts.add("limit:" + (r.nextInt(4) == 0 ? -1 : 1 + r.nextInt(12)));
      if (r.nextInt(3) == 0) parts.add("offset:" + r.nextInt(4));
      // numeric fields have no mincount 0
      if (r.nextInt(3) == 0)
        parts.add(
            "mincount:"
                + (field.endsWith("_s") || field.endsWith("_ss")
                    ? r.nextInt(3)
                    : 1 + r.nextInt(2)));
      if (r.nextInt(4) == 0) parts.add("missing:true");
      if (r.nextInt(4) == 0) parts.add("numBuckets:true");
      StringBuilder stats = new StringBuilder();
      int n = r.nextInt(4);
      for (int i = 0; i < n; i++) {
        stats.append(i > 0 ? "," : "").append("a").append(i).append(":'");
        stats.append(aggs[r.nextInt(aggs.length)]).append("'");
      }
      if (n > 0) {
        parts.add("facet:{" + stats + "}");
        if (r.nextBoolean())
          parts.add("sort:'a" + r.nextInt(n) + (r.nextBoolean() ? " asc'" : " desc'"));
      } else if (r.nextBoolean()) {
        parts.add("sort:'index " + (r.nextBoolean() ? "asc'" : "desc'"));
      }
      String json = "{f:{" + String.join(",", parts) + "}}";
      same("q", QUERIES[r.nextInt(QUERIES.length)], "rows", "0", "json.facet", json);
    }
    for (int iter = 0; iter < 20; iter++) {
      int start = 1985 + r.nextInt(20);
      String json =
          String.format(
              Locale.ROOT,
              "{r:{type:range,field:year_i,start:%d,end:%d,gap:%d,other:%s,include:%s,"
                  + "facet:{s:'%s'}}}",
              start,
              start + 5 + r.nextInt(40),
              1 + r.nextInt(7),
              r.nextBoolean() ? "all" : "none",
              r.nextBoolean() ? "lower" : "[upper,outer]",
              aggs[r.nextInt(aggs.length)]);
      same("q", QUERIES[r.nextInt(QUERIES.length)], "rows", "0", "json.facet", json);
    }
  }

  private static long accelerated(int kind) {
    return LuxirFacetAccelerator.ACCELERATED.get(kind);
  }
}
