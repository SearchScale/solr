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
import java.util.List;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.valuesource.DoubleFieldSource;
import org.apache.lucene.queries.function.valuesource.FloatFieldSource;
import org.apache.lucene.queries.function.valuesource.IntFieldSource;
import org.apache.lucene.queries.function.valuesource.LongFieldSource;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.schema.DatePointField;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;

/**
 * Fast paths for the JSON Facet API's document collection, supplied by {@link
 * FacetModule#accelerator}: for example, a codec that keeps doc values natively can count and
 * aggregate documents itself instead of a per-document loop in Java. Each method either collects
 * everything it is asked to, exactly as the processor would, and returns true, or changes nothing
 * and returns false, and the processor collects as usual. Processors keep everything else: buckets,
 * sorting, limits, sub-facets, refinement and merging.
 */
public interface FacetAccelerator {

  /** How an aggregation updates its per-slot accumulators for a collected (doc, slot). */
  enum Op {
    /** d0 += value (0 when missing): sum(). */
    SUM,
    /** d0 += value * value (0 when missing): sumsq(). */
    SUMSQ,
    /** if present: d0 += value, i0 += 1: avg(). */
    AVG,
    /** if present: d0 = min by Double.compare (NaN: unset): min() of a float or double. */
    MIN_DOUBLE,
    /** As {@link #MIN_DOUBLE}, the max. */
    MAX_DOUBLE,
    /** if present: l0 = min (unset: 0 and its exists bit clear): min() of an int or long. */
    MIN_LONG,
    /** As {@link #MIN_LONG}, the max. */
    MAX_LONG,
    /** if present: l0 = min (unset: Long.MIN_VALUE): min() of a date. */
    MIN_DATE,
    /** As {@link #MIN_DATE}, the max. */
    MAX_DATE,
    /** if present: l0 += 1: countvals(). */
    COUNT,
    /** if missing: l0 += 1: missing(). */
    MISSING,
    /** if present: i0 += 1, d0 += value * value, d1 += value: variance() and stddev(). */
    VARIANCE
  }

  /** How the field's numeric doc value reads, as Solr's field value source for it reads it. */
  enum Values {
    INT,
    LONG,
    FLOAT,
    DOUBLE
  }

  /**
   * One aggregation over a single-valued numeric field: the update and the slot accumulator's own
   * arrays it updates (null where unused). Valid until the accumulator is resized or reset.
   */
  record Agg(
      Op op,
      String field,
      Values values,
      double[] d0,
      double[] d1,
      long[] l0,
      int[] i0,
      FixedBitSet exists) {}

  /**
   * Collects {@code docs} to {@code slot} of the aggregations (the processor counts the docs
   * itself).
   */
  boolean collectSlot(FacetContext fcontext, DocSet docs, int slot, List<Agg> aggs)
      throws IOException;

  /**
   * Collects the base docs by their values of the SORTED or SORTED_SET {@code field}: each value
   * with global ordinal {@code g} (per {@code ordinalMap}, null when the index has one segment)
   * goes to slot {@code g - startTermIndex} if that is in [0, nTerms), where it is counted in
   * {@code counts} and aggregated.
   */
  boolean collectOrds(
      FacetContext fcontext,
      SchemaField field,
      OrdinalMap ordinalMap,
      int startTermIndex,
      int nTerms,
      SlotCounts counts,
      List<Agg> aggs)
      throws IOException;

  /**
   * Collects the base docs to every slot i whose {@code rangeQueries[i]} (a range query over the
   * numeric {@code field}, as the processor built it) matches them, counted in {@code counts} and
   * aggregated.
   */
  boolean collectRanges(
      FacetContext fcontext,
      SchemaField field,
      Query[] rangeQueries,
      SlotCounts counts,
      List<Agg> aggs)
      throws IOException;

  /** Receives counts per slot. */
  interface SlotCounts {
    void add(int slot, long count);
  }

  /**
   * Receives a numeric doc value and how many base docs have it; a value can come more than once,
   * its counts adding up.
   */
  interface ValueCount {
    /** Called before up to {@code n} more values (not counts) come; a sizing hint. */
    default void expect(int n) {}

    void accept(long value, long count) throws IOException;
  }

  /**
   * Counts the base docs per raw doc value of the numeric {@code field}, a doc once per value;
   * nothing is aggregated.
   */
  boolean countValues(FacetContext fcontext, SchemaField field, ValueCount out) throws IOException;

  /**
   * The aggregations that the accumulator updates, or null if any part of it is not a plain
   * aggregation over a single-valued numeric field's values (a function, a string, a multi-valued
   * field, unique(), percentile(), relatedness() and so on).
   */
  static List<Agg> describe(SlotAcc acc) {
    List<Agg> out = new ArrayList<>();
    return describe(acc, out) ? out : null;
  }

  private static boolean describe(SlotAcc acc, List<Agg> out) {
    if (acc instanceof FacetFieldProcessor.MultiAcc multi) {
      for (SlotAcc sub : multi.subAccs) {
        if (!describe(sub, out)) return false;
      }
      return true;
    }
    if (!(acc instanceof SlotAcc.FuncSlotAcc func)) return false;
    ValueSource vs = func.valueSource;
    Values values;
    if (vs.getClass() == IntFieldSource.class) {
      values = Values.INT;
    } else if (vs.getClass() == LongFieldSource.class
        || (vs instanceof LongFieldSource
            && vs.getClass().getEnclosingClass() == DatePointField.class)) {
      values = Values.LONG;
    } else if (vs.getClass() == FloatFieldSource.class) {
      values = Values.FLOAT;
    } else if (vs.getClass() == DoubleFieldSource.class) {
      values = Values.DOUBLE;
    } else {
      return false;
    }
    String field =
        ((org.apache.lucene.queries.function.valuesource.FieldCacheSource) vs).getField();
    Agg agg;
    if (acc.getClass() == SlotAcc.SumSlotAcc.class) {
      agg =
          new Agg(Op.SUM, field, values, ((SlotAcc.SumSlotAcc) acc).result, null, null, null, null);
    } else if (acc.getClass() == SlotAcc.SumsqSlotAcc.class) {
      agg =
          new Agg(
              Op.SUMSQ, field, values, ((SlotAcc.SumsqSlotAcc) acc).result, null, null, null, null);
    } else if (acc.getClass() == SlotAcc.AvgSlotAcc.class) {
      SlotAcc.AvgSlotAcc a = (SlotAcc.AvgSlotAcc) acc;
      agg = new Agg(Op.AVG, field, values, a.result, null, null, a.counts, null);
    } else if (acc.getClass() == SlotAcc.VarianceSlotAcc.class) {
      SlotAcc.VarianceSlotAcc a = (SlotAcc.VarianceSlotAcc) acc;
      agg = new Agg(Op.VARIANCE, field, values, a.result, a.sum, null, a.counts, null);
    } else if (acc.getClass() == SlotAcc.StddevSlotAcc.class) {
      SlotAcc.StddevSlotAcc a = (SlotAcc.StddevSlotAcc) acc;
      agg = new Agg(Op.VARIANCE, field, values, a.result, a.sum, null, a.counts, null);
    } else if (acc.getClass() == CountValsAgg.CountValSlotAcc.class) {
      agg =
          new Agg(
              Op.COUNT,
              field,
              values,
              null,
              null,
              ((SlotAcc.LongFuncSlotAcc) acc).result,
              null,
              null);
    } else if (acc.getClass() == MissingAgg.MissingSlotAcc.class) {
      agg =
          new Agg(
              Op.MISSING,
              field,
              values,
              null,
              null,
              ((SlotAcc.LongFuncSlotAcc) acc).result,
              null,
              null);
    } else if (acc instanceof MinMaxAgg.MinMaxAcc mm) {
      agg = mm.describe(field, values);
      if (agg == null) return false;
    } else {
      return false;
    }
    out.add(agg);
    return true;
  }
}
