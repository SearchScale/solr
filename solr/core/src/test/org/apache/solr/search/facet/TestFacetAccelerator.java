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
package org.apache.solr.search.facet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.LongValues;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.facet.FacetAccelerator.Agg;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * JSON facets collected through a {@link FacetAccelerator} must answer exactly as without one. The
 * accelerator here does in Java what the contract asks (reading doc values as Solr's field value
 * sources do, updating each accumulator by its own rule, in doc order); a second one always
 * declines.
 */
public class TestFacetAccelerator extends SolrTestCaseJ4 {
  static final int SLOT = 0, ORDS = 1, RANGES = 2, VALUES = 3;
  static final AtomicLongArray CALLS = new AtomicLongArray(4);

  /** Supplies the accelerator named by the {@code accel} param: java, decline, or none. */
  public static class Module extends FacetModule {
    @Override
    protected FacetAccelerator accelerator(ResponseBuilder rb) {
      return switch (rb.req.getParams().get("accel", "none")) {
        case "java" -> new JavaAccelerator();
        case "decline" -> new DecliningAccelerator();
        default -> null;
      };
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig-facet-accelerator.xml", "schema_latest.xml");
    String[] cats = {"A", "B", "C", "D", "E"};
    int numDocs = atLeast(1500);
    for (int i = 0; i < numDocs; i++) {
      List<String> f = new ArrayList<>(List.of("id", "" + i));
      if (random().nextInt(10) > 0) f.addAll(List.of("cat_sd", cats[random().nextInt(5)]));
      for (int c = random().nextInt(3); c > 0; c--) {
        f.addAll(List.of("cats_sds", cats[random().nextInt(5)]));
      }
      // many distinct values, so the numeric terms table grows while counting
      if (random().nextInt(10) > 0) f.addAll(List.of("n_id", "" + random().nextInt(numDocs)));
      if (random().nextInt(10) > 0) f.addAll(List.of("n_ld", "" + (random().nextLong() >> 20)));
      if (random().nextInt(10) > 0) {
        f.addAll(List.of("x_fd", "" + (random().nextFloat() * 200 - 100)));
      }
      if (random().nextInt(10) > 0) {
        f.addAll(List.of("x_dd", "" + (random().nextDouble() * 2000 - 1000)));
      }
      if (random().nextInt(10) > 0) {
        f.addAll(List.of("t_dtd", "2020-0" + (1 + random().nextInt(9)) + "-15T10:00:00Z"));
      }
      assertU(adoc(f.toArray(new String[0])));
      if (random().nextInt(300) == 0) assertU(commit()); // several segments: global ordinals
    }
    for (int i = 0; i < numDocs; i += 97) assertU(delI("" + i));
    assertU(commit());
  }

  private static final String STATS =
      "s:'sum(x_dd)',sq:'sumsq(x_fd)',a:'avg(n_id)',mn:'min(n_ld)',mx:'max(x_fd)',"
          + "md:'min(x_dd)',d:'max(t_dtd)',v:'variance(x_dd)',sd:'stddev(n_id)',"
          + "cv:'countvals(x_dd)',ms:'missing(n_id)'";

  private static final String[] FACETS = {
    // a domain's stats, and a query facet's
    "{" + STATS + "}",
    "{q:{type:query,q:'cat_sd:B',facet:{" + STATS + "}}}",
    // terms over SORTED and SORTED_SET doc values, with and without stats
    "{c:{type:terms,field:cat_sd,missing:true,numBuckets:true}}",
    "{c:{type:terms,field:cat_sd,facet:{" + STATS + "}}}",
    "{c:{type:terms,field:cats_sds,prefix:B,facet:{" + STATS + "}}}",
    "{c:{type:terms,field:cats_sds,offset:1,limit:2,facet:{s:'sum(x_dd)'}}}",
    // sorted by one stat: the others are collected per bucket afterwards
    "{c:{type:terms,field:cat_sd,sort:'s desc',limit:3,facet:{" + STATS + "}}}",
    // ranges, with every bucket and the others in one pass
    "{r:{type:range,field:n_id,start:0,end:1500,gap:100,other:all,facet:{" + STATS + "}}}",
    "{r:{type:range,field:x_dd,start:-1000,end:1000,gap:250,include:[lower,upper,edge]}}",
    "{r:{type:range,field:t_dtd,start:'2020-01-01T00:00:00Z',end:'2020-12-01T00:00:00Z',"
        + "gap:'+2MONTHS',other:before,facet:{a:'avg(x_fd)'}}}",
    "{r:{type:range,field:n_ld,ranges:[{to:0},{from:-1000000000,to:1000000000},{from:0}]}}",
    // numeric terms counts
    "{h:{type:terms,field:n_id,limit:-1}}",
    "{h:{type:terms,field:x_fd,limit:20,sort:'index asc'}}",
  };

  private static final String[][] QUERIES = {
    {"q", "*:*"}, {"q", "cat_sd:A"}, {"q", "n_id:[100 TO 900]", "fq", "-cats_sds:C"},
  };

  @Test
  public void testSameResponses() throws Exception {
    for (String facet : FACETS) {
      for (String[] query : QUERIES) {
        String expected = response(facet, query, "none");
        assertEquals(
            facet + " " + String.join(" ", query), expected, response(facet, query, "java"));
        assertEquals(facet, expected, response(facet, query, "decline"));
      }
    }
    for (int i = 0; i < CALLS.length(); i++) {
      assertTrue("accelerator method " + i + " never collected", CALLS.get(i) > 0);
    }
  }

  private String response(String facet, String[] query, String accel) throws Exception {
    List<String> params = new ArrayList<>(List.of(query));
    params.addAll(
        List.of(
            "rows", "0", "omitHeader", "true", "wt", "json", "json.facet", facet, "accel", accel));
    return h.query(req(params.toArray(new String[0])));
  }

  static final class DecliningAccelerator implements FacetAccelerator {
    @Override
    public boolean collectSlot(FacetContext fcontext, DocSet docs, int slot, List<Agg> aggs) {
      return false;
    }

    @Override
    public boolean collectOrds(
        FacetContext fcontext,
        SchemaField field,
        OrdinalMap ordinalMap,
        int startTermIndex,
        int nTerms,
        SlotCounts counts,
        List<Agg> aggs) {
      return false;
    }

    @Override
    public boolean collectRanges(
        FacetContext fcontext,
        SchemaField field,
        Query[] rangeQueries,
        SlotCounts counts,
        List<Agg> aggs) {
      return false;
    }

    @Override
    public boolean countValues(FacetContext fcontext, SchemaField field, ValueCount out) {
      return false;
    }
  }

  static final class JavaAccelerator implements FacetAccelerator {
    @Override
    public boolean collectSlot(FacetContext fcontext, DocSet docs, int slot, List<Agg> aggs)
        throws IOException {
      Aggregator aggregator = new Aggregator(fcontext.getSearcher(), aggs);
      for (DocIterator it = docs.iterator(); it.hasNext(); ) aggregator.collect(it.nextDoc(), slot);
      CALLS.incrementAndGet(SLOT);
      return true;
    }

    @Override
    public boolean collectOrds(
        FacetContext fcontext,
        SchemaField field,
        OrdinalMap ordinalMap,
        int startTermIndex,
        int nTerms,
        SlotCounts counts,
        List<Agg> aggs)
        throws IOException {
      SolrIndexSearcher searcher = fcontext.getSearcher();
      List<LeafReaderContext> leaves = searcher.getTopReaderContext().leaves();
      Aggregator aggregator = new Aggregator(searcher, aggs);
      int leafOrd = -1;
      SortedSetDocValues dv = null;
      LongValues toGlobal = null;
      for (DocIterator it = fcontext.getBase().iterator(); it.hasNext(); ) {
        int doc = it.nextDoc();
        if (leafOrd < 0
            || doc >= leaves.get(leafOrd).docBase + leaves.get(leafOrd).reader().maxDoc()) {
          leafOrd = ReaderUtil.subIndex(doc, leaves);
          dv = DocValues.getSortedSet(leaves.get(leafOrd).reader(), field.getName());
          toGlobal = ordinalMap == null ? null : ordinalMap.getGlobalOrds(leafOrd);
        }
        if (!dv.advanceExact(doc - leaves.get(leafOrd).docBase)) continue;
        for (int i = 0; i < dv.docValueCount(); i++) {
          long ord = dv.nextOrd();
          long slot = (toGlobal == null ? ord : toGlobal.get(ord)) - startTermIndex;
          if (slot < 0 || slot >= nTerms) continue;
          counts.add((int) slot, 1);
          aggregator.collect(doc, (int) slot);
        }
      }
      CALLS.incrementAndGet(ORDS);
      return true;
    }

    @Override
    public boolean collectRanges(
        FacetContext fcontext,
        SchemaField field,
        Query[] rangeQueries,
        SlotCounts counts,
        List<Agg> aggs)
        throws IOException {
      SolrIndexSearcher searcher = fcontext.getSearcher();
      for (int slot = 0; slot < rangeQueries.length; slot++) {
        DocSet docs = searcher.getDocSet(rangeQueries[slot]).intersection(fcontext.getBase());
        if (docs.size() > 0) counts.add(slot, docs.size());
        Aggregator aggregator = new Aggregator(searcher, aggs);
        for (DocIterator it = docs.iterator(); it.hasNext(); )
          aggregator.collect(it.nextDoc(), slot);
      }
      CALLS.incrementAndGet(RANGES);
      return true;
    }

    @Override
    public boolean countValues(FacetContext fcontext, SchemaField field, ValueCount out)
        throws IOException {
      if (field.multiValued()) return false;
      SolrIndexSearcher searcher = fcontext.getSearcher();
      List<LeafReaderContext> leaves = searcher.getTopReaderContext().leaves();
      Map<Long, Long> counts = new HashMap<>();
      int leafOrd = -1;
      NumericDocValues dv = null;
      for (DocIterator it = fcontext.getBase().iterator(); it.hasNext(); ) {
        int doc = it.nextDoc();
        if (leafOrd < 0
            || doc >= leaves.get(leafOrd).docBase + leaves.get(leafOrd).reader().maxDoc()) {
          leafOrd = ReaderUtil.subIndex(doc, leaves);
          dv = DocValues.getNumeric(leaves.get(leafOrd).reader(), field.getName());
        }
        if (dv.advanceExact(doc - leaves.get(leafOrd).docBase)) {
          counts.merge(dv.longValue(), 1L, Long::sum);
        }
      }
      out.expect(counts.size());
      for (Map.Entry<Long, Long> e : counts.entrySet()) out.accept(e.getKey(), e.getValue());
      CALLS.incrementAndGet(VALUES);
      return true;
    }
  }

  /** Applies aggregations to (doc, slot) pairs given in ascending doc order. */
  static final class Aggregator {
    final List<LeafReaderContext> leaves;
    final List<Agg> aggs;
    final NumericDocValues[] values;
    final boolean[] present;
    int leafOrd = -1, lastDoc = -1;

    Aggregator(SolrIndexSearcher searcher, List<Agg> aggs) {
      this.leaves = searcher.getTopReaderContext().leaves();
      this.aggs = aggs;
      this.values = new NumericDocValues[aggs.size()];
      this.present = new boolean[aggs.size()];
    }

    void collect(int doc, int slot) throws IOException {
      if (aggs.isEmpty()) return;
      if (leafOrd < 0
          || doc >= leaves.get(leafOrd).docBase + leaves.get(leafOrd).reader().maxDoc()) {
        leafOrd = ReaderUtil.subIndex(doc, leaves);
        for (int a = 0; a < aggs.size(); a++) {
          values[a] = DocValues.getNumeric(leaves.get(leafOrd).reader(), aggs.get(a).field());
        }
      }
      int segDoc = doc - leaves.get(leafOrd).docBase;
      boolean newDoc = doc != lastDoc; // a doc comes once per slot it goes to
      lastDoc = doc;
      for (int a = 0; a < aggs.size(); a++) {
        Agg agg = aggs.get(a);
        if (newDoc) present[a] = values[a].advanceExact(segDoc);
        long raw = present[a] ? values[a].longValue() : 0;
        double d =
            switch (agg.values()) {
              case INT -> (int) raw;
              case LONG -> raw;
              case FLOAT -> Float.intBitsToFloat((int) raw);
              case DOUBLE -> Double.longBitsToDouble(raw);
            };
        long l = agg.values() == FacetAccelerator.Values.INT ? (int) raw : raw;
        apply(agg, slot, present[a], d, l);
      }
    }

    private static void apply(Agg agg, int slot, boolean present, double d, long l) {
      switch (agg.op()) {
        case SUM -> agg.d0()[slot] += d;
        case SUMSQ -> agg.d0()[slot] += d * d;
        case AVG -> {
          if (present) {
            agg.d0()[slot] += d;
            agg.i0()[slot]++;
          }
        }
        case VARIANCE -> {
          if (present) {
            agg.i0()[slot]++;
            agg.d0()[slot] += d * d;
            agg.d1()[slot] += d;
          }
        }
        case MIN_DOUBLE, MAX_DOUBLE -> {
          int sign = agg.op() == FacetAccelerator.Op.MIN_DOUBLE ? 1 : -1;
          double cur = agg.d0()[slot];
          if (present && (Double.compare(d, cur) * sign < 0 || Double.isNaN(cur))) {
            agg.d0()[slot] = d;
          }
        }
        case MIN_LONG, MAX_LONG -> {
          int sign = agg.op() == FacetAccelerator.Op.MIN_LONG ? 1 : -1;
          if (!present) return;
          if (agg.l0()[slot] == 0 && !agg.exists().get(slot)) {
            agg.exists().set(slot);
            agg.l0()[slot] = l;
          } else if (Long.compare(l, agg.l0()[slot]) * sign < 0) {
            agg.l0()[slot] = l;
          }
        }
        case MIN_DATE, MAX_DATE -> {
          int sign = agg.op() == FacetAccelerator.Op.MIN_DATE ? 1 : -1;
          long cur = agg.l0()[slot];
          if (present && (Long.compare(l, cur) * sign < 0 || cur == Long.MIN_VALUE)) {
            agg.l0()[slot] = l;
          }
        }
        case COUNT -> {
          if (present) agg.l0()[slot]++;
        }
        case MISSING -> {
          if (!present) agg.l0()[slot]++;
        }
      }
    }
  }
}
