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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.LongValues;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SortedIntDocSet;
import org.apache.solr.search.facet.FacetAccelerator;
import org.apache.solr.search.facet.FacetAccelerator.Op;
import org.apache.solr.search.facet.FacetContext;
import org.luxir.lucene.search.DocMatches;
import org.luxir.lucene.search.LuxirFacets;
import org.luxir.lucene.search.NativeFacetCollector;

/**
 * JSON facet collection by Luxir, for fields whose doc values every segment keeps natively: terms
 * facets on string fields, range facets on point fields, numeric terms counts, and the plain
 * aggregations (sum, avg, min, max, sumsq, countvals, missing, variance, stddev) of single-valued
 * point fields, for buckets and for a whole domain; for a whole domain also unique() and hll() of
 * string and point fields. Each segment's documents are counted and
 * aggregated in one native pass with the same rules as Solr's slot accumulators, in doc order, so
 * results are identical. Anything else is left to Solr.
 */
final class LuxirFacetAccelerator implements FacetAccelerator {
  static final LuxirFacetAccelerator INSTANCE = new LuxirFacetAccelerator();

  /** Collections done natively, by kind: slot, ordinals, ranges, values (read by tests). */
  static final AtomicLongArray ACCELERATED = new AtomicLongArray(4);

  static final int SLOT = 0, ORDS = 1, RANGES = 2, VALUES = 3;

  /**
   * Docs with a value sampled before numeric values are judged mostly distinct (see {@link
   * LuxirFacets#valueCounts(LeafReaderContext, String, DocMatches, int, LuxirFacets.ValueSink)});
   * tests lower it to reach that path on small indexes.
   */
  static int nearUniqueSample = LuxirFacets.NEAR_UNIQUE_SAMPLE;

  private LuxirFacetAccelerator() {}

  @Override
  public boolean collectSlot(FacetContext fcontext, DocSet docs, int slot, List<Agg> aggs)
      throws IOException {
    if (aggs.isEmpty()) return true; // nothing to aggregate: the processor counts the docs
    SolrIndexSearcher searcher = fcontext.getSearcher();
    List<Agg> plain = new ArrayList<>(aggs.size());
    List<Agg> unique = new ArrayList<>();
    for (Agg a : aggs) (unique(a) ? unique : plain).add(a);
    List<NativeFacetCollector.Agg> nativeAggs = nativeAggs(searcher, plain);
    if (nativeAggs == null) return false;
    // unique() reads every segment's bits: small domains (a bucket's) stay with Solr
    if (!unique.isEmpty() && docs.size() < LuxirStatsAccelerator.minDocs) return false;
    for (Agg a : unique) {
      if (!uniqueField(searcher, a)) return false;
    }
    if (!nativeAggs.isEmpty()) {
      DocMatches matches = matches(docs, searcher.maxDoc());
      try (NativeFacetCollector c = new NativeFacetCollector(nativeAggs, slot, 0)) {
        for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
          c.collectSlot(leaf, matches, slot);
        }
        c.finish();
      }
    }
    if (!unique.isEmpty()) collectUnique(searcher, docs, slot, unique);
    ACCELERATED.incrementAndGet(SLOT);
    return true;
  }

  private static boolean unique(Agg a) {
    return a.op() == Op.UNIQUE_ORDS || a.op() == Op.UNIQUE_VALUES;
  }

  /** Whether a unique() or hll() reads doc values every segment keeps natively. */
  private static boolean uniqueField(SolrIndexSearcher searcher, Agg a) {
    SchemaField sf = searcher.getSchema().getFieldOrNull(a.field());
    if (sf == null || !sf.hasDocValues()) return false;
    if (a.op() == Op.UNIQUE_VALUES) return pointField(searcher, sf);
    DocValuesType dv = sf.multiValued() ? DocValuesType.SORTED_SET : DocValuesType.SORTED;
    return LuxirFacets.nativeField(searcher, sf.getName(), dv);
  }

  /**
   * unique() and hll() of the docs into one slot: each segment's touched ordinals (mapped to
   * global ones) or distinct values, counted natively, go to the accumulator as Solr's per-doc
   * collection would put them.
   */
  private static void collectUnique(SolrIndexSearcher searcher, DocSet docs, int slot, List<Agg> unique)
      throws IOException {
    org.apache.lucene.util.FixedBitSet bits = LuxirCodecQueryComponent.bits(docs, searcher.maxDoc());
    DocMatches matches = DocMatches.of(bits);
    for (Agg a : unique) {
      String field = a.field();
      if (a.op() == Op.UNIQUE_ORDS) {
        FacetAccelerator.UniqueOrds target = (FacetAccelerator.UniqueOrds) a.target();
        OrdinalMap ordMap = target.ordinalMap();
        org.apache.lucene.util.FixedBitSet set = target.slot(slot);
        for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
          int segOrds = (int) DocValues.getSortedSet(leaf.reader(), field).getValueCount();
          if (segOrds == 0) continue;
          LongValues toGlobal = ordMap == null ? null : ordMap.getGlobalOrds(leaf.ord);
          for (int o : LuxirFacets.sparseOrdCounts(leaf, field, bits, segOrds).ords()) {
            set.set(toGlobal == null ? o : (int) toGlobal.get(o));
          }
        }
      } else {
        FacetAccelerator.UniqueValues target = (FacetAccelerator.UniqueValues) a.target();
        LuxirFacets.ValueSink sink = (value, count, firstDoc) -> target.add(slot, value);
        for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
          LuxirFacets.valueCounts(leaf, field, matches, nearUniqueSample, sink);
        }
      }
    }
  }

  @Override
  public boolean collectOrds(
      FacetContext fcontext,
      SchemaField field,
      OrdinalMap ordinalMap,
      int startTermIndex,
      int nTerms,
      SlotCounts slotCounts,
      List<Agg> aggs)
      throws IOException {
    SolrIndexSearcher searcher = fcontext.getSearcher();
    DocValuesType type = field.multiValued() ? DocValuesType.SORTED_SET : DocValuesType.SORTED;
    if (!field.hasDocValues() || !LuxirFacets.nativeField(searcher, field.getName(), type)) {
      return false;
    }
    List<NativeFacetCollector.Agg> nativeAggs = nativeAggs(searcher, aggs);
    if (nativeAggs == null) return false;
    // Native counting maps every segment ordinal to its slot and hands back a count per slot: work
    // in proportion to the field's terms. With few matches among many terms (a near-unique field
    // over a filter), Solr's per-doc counting does less.
    long segOrdsTotal = 0;
    for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
      segOrdsTotal += DocValues.getSortedSet(leaf.reader(), field.getName()).getValueCount();
    }
    if (fcontext.getBase().size() * 4L < segOrdsTotal) return false;
    DocMatches matches = matches(fcontext.getBase(), searcher.maxDoc());
    long[] counts;
    try (NativeFacetCollector c = new NativeFacetCollector(nativeAggs, -1, nTerms)) {
      for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
        int segOrds = (int) DocValues.getSortedSet(leaf.reader(), field.getName()).getValueCount();
        if (segOrds == 0) continue;
        LongValues toGlobal = ordinalMap == null ? null : ordinalMap.getGlobalOrds(leaf.ord);
        int[] ordSlots = new int[segOrds];
        for (int o = 0; o < segOrds; o++) {
          long g = toGlobal == null ? o : toGlobal.get(o);
          long s = g - startTermIndex;
          ordSlots[o] = s >= 0 && s < nTerms ? (int) s : -1;
        }
        c.collectOrds(leaf, matches, field.getName(), ordSlots);
      }
      counts = c.finish();
    }
    addCounts(slotCounts, counts);
    ACCELERATED.incrementAndGet(ORDS);
    return true;
  }

  @Override
  public boolean collectRanges(
      FacetContext fcontext,
      SchemaField field,
      Query[] rangeQueries,
      SlotCounts slotCounts,
      List<Agg> aggs)
      throws IOException {
    SolrIndexSearcher searcher = fcontext.getSearcher();
    if (!pointField(searcher, field)) return false;
    long[][] bounds = LuxirRangeFacetProcessor.bounds(field, rangeQueries);
    if (bounds == null) return false;
    List<NativeFacetCollector.Agg> nativeAggs = nativeAggs(searcher, aggs);
    if (nativeAggs == null) return false;
    if (nativeAggs.isEmpty() && fewRangesOverMostDocs(rangeQueries.length, fcontext.getBase().size(), searcher.maxDoc())) {
      return false;
    }
    DocMatches matches = matches(fcontext.getBase(), searcher.maxDoc());
    int kind = LuxirRangeFacetProcessor.kind(field);
    long[] counts;
    try (NativeFacetCollector c =
        new NativeFacetCollector(nativeAggs, -1, Math.max(1, rangeQueries.length))) {
      for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
        c.collectRanges(leaf, matches, field.getName(), kind, bounds[0], bounds[1]);
      }
      counts = c.finish();
    }
    addCounts(slotCounts, counts);
    ACCELERATED.incrementAndGet(RANGES);
    return true;
  }

  /**
   * Whether counting a few ranges over (nearly) every doc is better left to Solr: it counts each
   * range's docs from the points (and its filter cache keeps them across requests) without visiting
   * every matching doc's values, as native collection does.
   */
  static boolean fewRangesOverMostDocs(int ranges, int baseSize, int maxDoc) {
    return ranges <= 64 && baseSize * 10L >= maxDoc * 9L;
  }

  @Override
  public boolean countValues(FacetContext fcontext, SchemaField field, ValueCount out)
      throws IOException {
    SolrIndexSearcher searcher = fcontext.getSearcher();
    if (!pointField(searcher, field)) return false;
    List<LeafReaderContext> leaves = searcher.getTopReaderContext().leaves();
    for (LeafReaderContext leaf : leaves) {
      if (!LuxirFacets.nativeValues(leaf, field.getName())) return false; // checked above
    }
    DocMatches matches = matches(fcontext.getBase(), searcher.maxDoc());
    LuxirFacets.ValueSink sink =
        new LuxirFacets.ValueSink() {
          @Override
          public void expect(int n) {
            out.expect(n);
          }

          @Override
          public void accept(long value, int count, int firstDoc) throws IOException {
            out.accept(value, count);
          }
        };
    for (LeafReaderContext leaf : leaves) {
      LuxirFacets.valueCounts(leaf, field.getName(), matches, nearUniqueSample, sink);
    }
    ACCELERATED.incrementAndGet(VALUES);
    return true;
  }

  /** A doc set for the native collectors: a sorted int set's own docs, else bits. */
  static DocMatches matches(DocSet set, int maxDoc) {
    if (set instanceof SortedIntDocSet sorted)
      return DocMatches.of(sorted.getDocs(), sorted.size());
    return DocMatches.of(LuxirCodecQueryComponent.bits(set, maxDoc));
  }

  private static void addCounts(SlotCounts slotCounts, long[] counts) {
    for (int slot = 0; slot < counts.length; slot++) {
      if (counts[slot] != 0) slotCounts.add(slot, counts[slot]);
    }
  }

  /** A point field with doc values that every segment keeps natively. */
  private static boolean pointField(SolrIndexSearcher searcher, SchemaField sf) {
    if (!sf.getType().isPointField()
        || !sf.hasDocValues()
        || sf.getType().getNumberType() == null) {
      return false;
    }
    DocValuesType dv = sf.multiValued() ? DocValuesType.SORTED_NUMERIC : DocValuesType.NUMERIC;
    return LuxirFacets.nativeField(searcher, sf.getName(), dv);
  }

  /**
   * The aggregations as native ones: each over a single-valued point field with doc values kept
   * natively in every segment; null if one is not.
   */
  // Solr's operations and value types as the native collector's (same names), by ordinal
  private static final NativeFacetCollector.Op[] NATIVE_OPS =
      java.util.Arrays.stream(Op.values()).map(o -> nativeEnum(NativeFacetCollector.Op.class, o.name()))
          .toArray(NativeFacetCollector.Op[]::new);
  private static final NativeFacetCollector.ValueType[] NATIVE_VALUES =
      java.util.Arrays.stream(Values.values()).map(v -> nativeEnum(NativeFacetCollector.ValueType.class, v.name()))
          .toArray(NativeFacetCollector.ValueType[]::new);

  private static <E extends Enum<E>> E nativeEnum(Class<E> type, String name) {
    for (E e : type.getEnumConstants()) {
      if (e.name().equals(name)) return e;
    }
    return null; // not a native operation (its aggregations are left to Solr before they get here)
  }

  private static List<NativeFacetCollector.Agg> nativeAggs(
      SolrIndexSearcher searcher, List<Agg> aggs) {
    List<NativeFacetCollector.Agg> out = new ArrayList<>(aggs.size());
    for (Agg a : aggs) {
      if (unique(a)) return null; // per slot of many, only Solr keeps them
      SchemaField sf = searcher.getSchema().getFieldOrNull(a.field());
      if (sf == null || sf.multiValued() || !pointField(searcher, sf)) return null;
      out.add(
          new NativeFacetCollector.Agg(
              NATIVE_OPS[a.op().ordinal()],
              a.field(),
              NATIVE_VALUES[a.values().ordinal()],
              a.d0(),
              a.d1(),
              a.l0(),
              a.i0(),
              a.exists() == null ? null : a.exists().getBits()));
    }
    return out;
  }
}
