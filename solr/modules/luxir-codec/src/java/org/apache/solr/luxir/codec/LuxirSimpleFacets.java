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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.PriorityQueue;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.GroupParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SimpleFacets;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.util.LongPriorityQueue;
import org.luxir.lucene.search.LuxirFacets;

/**
 * {@link SimpleFacets} whose field facets on doc values kept by Luxir are counted natively: one
 * scan of the field's column per segment over the matching documents. The counts are then selected,
 * sorted and rendered exactly as Solr's {@code DocValuesFacets} (strings) and {@code NumericFacets}
 * (point fields) do. Facets with a prefix, term filter, {@code facet.exists}, grouping, or an
 * explicit enum/uif method, and fields not kept natively, take Solr's path.
 */
public class LuxirSimpleFacets extends SimpleFacets {

  public LuxirSimpleFacets(
      SolrQueryRequest req, DocSet docs, SolrParams params, ResponseBuilder rb) {
    super(req, docs, params, rb);
  }

  @Override
  public NamedList<Integer> getTermCounts(String field, ParsedParams parsed) throws IOException {
    NamedList<Integer> counts = nativeTermCounts(field, parsed);
    return counts != null ? counts : super.getTermCounts(field, parsed);
  }

  private NamedList<Integer> nativeTermCounts(String field, ParsedParams parsed)
      throws IOException {
    SolrParams params = parsed.params;
    if (params.getFieldBool(field, GroupParams.GROUP_FACET, false)
        || params.getFieldBool(field, FacetParams.FACET_EXISTS, false)
        || params.getFieldParam(field, FacetParams.FACET_PREFIX) != null
        || params.getFieldParam(field, FacetParams.FACET_CONTAINS) != null
        || params.getFieldParam(field, FacetParams.FACET_EXCLUDETERMS) != null
        || params.getFieldParam(field, FacetParams.FACET_MATCHES) != null) {
      return null;
    }
    String method = params.getFieldParam(field, FacetParams.FACET_METHOD);
    if (FacetParams.FACET_METHOD_enum.equals(method)
        || FacetParams.FACET_METHOD_uif.equals(method)) {
      return null;
    }
    SchemaField sf = searcher.getSchema().getFieldOrNull(field);
    if (sf == null || !sf.hasDocValues()) return null;
    FieldType ft = sf.getType();
    boolean numeric = ft.isPointField() && ft.getNumberType() != null && !sf.multiValued();
    boolean string = ft.getNumberType() == null && !ft.isTokenized() && !ft.isPointField();
    DocValuesType type =
        numeric
            ? DocValuesType.NUMERIC
            : sf.multiValued() ? DocValuesType.SORTED_SET : DocValuesType.SORTED;
    if (!(numeric || string) || !LuxirFacets.nativeField(searcher, field, type)) return null;

    // the parameters as SimpleFacets reads them
    Integer mincount = params.getFieldInt(field, FacetParams.FACET_MINCOUNT);
    int offset = params.getFieldInt(field, FacetParams.FACET_OFFSET, 0);
    int limit = params.getFieldInt(field, FacetParams.FACET_LIMIT, 100);
    boolean missing = params.getFieldBool(field, FacetParams.FACET_MISSING, false);
    if (limit == 0 && !missing) return new NamedList<>();
    if (mincount == null) {
      Boolean zeros = params.getFieldBool(field, FacetParams.FACET_ZEROS);
      mincount = (zeros != null && !zeros) ? 1 : 0;
    }
    String sort =
        params.getFieldParam(
            field,
            FacetParams.FACET_SORT,
            limit > 0 ? FacetParams.FACET_SORT_COUNT : FacetParams.FACET_SORT_INDEX);
    FixedBitSet docs = LuxirCodecQueryComponent.bits(parsed.docs, searcher.maxDoc());
    if (numeric) {
      if (mincount <= 0) {
        // as SimpleFacets does for point fields (SOLR-10033, SOLR-11174)
        String warning =
            "Raising facet.mincount from "
                + mincount
                + " to 1, because field "
                + field
                + " is Points-based.";
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) rb.rsp.getResponseHeader().get("warnings");
        if (warnings == null) {
          warnings = new ArrayList<>();
          rb.rsp.getResponseHeader().add("warnings", warnings);
        }
        warnings.add(warning);
        mincount = 1;
      }
      return numericCounts(sf, docs, offset, limit, mincount, missing, sort);
    }
    return stringCounts(sf, parsed.docs, docs, offset, limit, mincount, missing, sort);
  }

  /** DocValuesFacets.getCounts without prefix or term filter, counting natively. */
  private NamedList<Integer> stringCounts(
      SchemaField schemaField,
      DocSet docSet,
      FixedBitSet docs,
      int offset,
      int limit,
      int mincount,
      boolean missing,
      String sort)
      throws IOException {
    String fieldName = schemaField.getName();
    FieldType ft = schemaField.getType();
    NamedList<Integer> res = new NamedList<>();
    boolean multiValued = schemaField.multiValued();

    final SortedSetDocValues si; // for term lookups only
    OrdinalMap ordinalMap = null; // for mapping per-segment ords to global ones
    if (multiValued) {
      si = searcher.getSlowAtomicReader().getSortedSetDocValues(fieldName);
      if (si instanceof MultiDocValues.MultiSortedSetDocValues m) ordinalMap = m.mapping;
    } else {
      SortedDocValues single = searcher.getSlowAtomicReader().getSortedDocValues(fieldName);
      si = single == null ? null : DocValues.singleton(single);
      if (single instanceof MultiDocValues.MultiSortedDocValues m) ordinalMap = m.mapping;
    }
    if (si == null) return finish(res, schemaField, docSet, -1, missing);
    if (si.getValueCount() >= Integer.MAX_VALUE) return null;

    final int nTerms = (int) si.getValueCount() + 1; // [0]: missing
    int missingCount = -1;
    final CharsRefBuilder charsRef = new CharsRefBuilder();
    if (docSet.size() >= mincount) {
      // touched global ordinals and counts, natively per segment: (ord << 32 | count), by ord
      long[] pairs = new long[16];
      int numPairs = 0;
      int missingDocs = 0;
      List<LeafReaderContext> leaves = searcher.getTopReaderContext().leaves();
      for (int subIndex = 0; subIndex < leaves.size(); subIndex++) {
        LeafReaderContext leaf = leaves.get(subIndex);
        // SORTED fields too (as singletons); empty if the segment has no values
        int segTerms = (int) DocValues.getSortedSet(leaf.reader(), fieldName).getValueCount();
        LuxirFacets.OrdCounts seg = LuxirFacets.sparseOrdCounts(leaf, fieldName, docs, segTerms);
        if (seg == null) return null;
        missingDocs += seg.missing();
        LongValues toGlobal = ordinalMap == null ? null : ordinalMap.getGlobalOrds(subIndex);
        if (numPairs + seg.ords().length > pairs.length) {
          pairs = Arrays.copyOf(pairs, Math.max(pairs.length * 2, numPairs + seg.ords().length));
        }
        for (int i = 0; i < seg.ords().length; i++) {
          long g = toGlobal == null ? seg.ords()[i] : toGlobal.get(seg.ords()[i]);
          pairs[numPairs++] = (g << 32) | seg.counts()[i];
        }
      }
      // merge segments' counts for the same ordinal
      Arrays.sort(pairs, 0, numPairs);
      int merged = 0;
      for (int i = 0; i < numPairs; i++) {
        if (merged > 0 && (pairs[merged - 1] >>> 32) == (pairs[i] >>> 32)) {
          pairs[merged - 1] += (int) pairs[i];
        } else {
          pairs[merged++] = pairs[i];
        }
      }
      missingCount = missingDocs;
      if (limit == 0) return finish(res, schemaField, docSet, missingCount, missing);

      boolean countSort =
          sort.equals(FacetParams.FACET_SORT_COUNT)
              || sort.equals(FacetParams.FACET_SORT_COUNT_LEGACY);
      // Zero counts never outrank positive ones, so the touched ordinals alone decide unless zeros
      // must fill the page (mincount 0, too few positives) or interleave (index order, mincount 0).
      boolean sparse = mincount > 0 || (countSort && limit >= 0 && merged >= (long) offset + limit);
      if (sparse) {
        int off = offset;
        int lim = limit >= 0 ? limit : Integer.MAX_VALUE;
        if (countSort) {
          int maxsize = limit > 0 ? offset + limit : Integer.MAX_VALUE - 1;
          maxsize = Math.min(maxsize, nTerms);
          LongPriorityQueue queue =
              new LongPriorityQueue(Math.min(maxsize, 1000), maxsize, Long.MIN_VALUE);
          int min = mincount - 1;
          for (int j = 0; j < merged; j++) {
            int c = (int) pairs[j];
            if (c > min) {
              int i = (int) (pairs[j] >>> 32) + 1;
              long pair = (((long) c) << 32) + (Integer.MAX_VALUE - i);
              if (queue.insert(pair)) min = (int) (queue.top() >>> 32);
            }
          }
          int collectCount = Math.max(0, queue.size() - off);
          int sortedIdxStart = queue.size() - (collectCount - 1);
          int sortedIdxEnd = queue.size() + 1;
          final long[] sorted = queue.sort(collectCount);
          for (int i = sortedIdxStart; i < sortedIdxEnd; i++) {
            long pair = sorted[i];
            int c = (int) (pair >>> 32);
            int tnum = Integer.MAX_VALUE - (int) pair;
            ft.indexedToReadable(si.lookupOrd(tnum - 1), charsRef);
            res.add(charsRef.toString(), c);
          }
        } else {
          for (int j = 0; j < merged; j++) {
            int c = (int) pairs[j];
            if (c < mincount) continue;
            if (--off >= 0) continue;
            if (--lim < 0) break;
            ft.indexedToReadable(si.lookupOrd(pairs[j] >>> 32), charsRef);
            res.add(charsRef.toString(), c);
          }
        }
        return finish(res, schemaField, docSet, missingCount, missing);
      }

      // dense: every ordinal, as DocValuesFacets selects them
      final int[] counts = new int[nTerms];
      counts[0] = missingDocs;
      for (int j = 0; j < merged; j++) counts[1 + (int) (pairs[j] >>> 32)] = (int) pairs[j];
      int off = offset;
      int lim = limit >= 0 ? limit : Integer.MAX_VALUE;
      if (sort.equals(FacetParams.FACET_SORT_COUNT)
          || sort.equals(FacetParams.FACET_SORT_COUNT_LEGACY)) {
        int maxsize = limit > 0 ? offset + limit : Integer.MAX_VALUE - 1;
        maxsize = Math.min(maxsize, nTerms);
        LongPriorityQueue queue =
            new LongPriorityQueue(Math.min(maxsize, 1000), maxsize, Long.MIN_VALUE);
        int min = mincount - 1; // the smallest value in the top 'N' values
        for (int i = 1; i < nTerms; i++) {
          int c = counts[i];
          if (c > min) {
            // smaller term numbers sort higher, so subtract the term number instead
            long pair = (((long) c) << 32) + (Integer.MAX_VALUE - i);
            boolean displaced = queue.insert(pair);
            if (displaced) min = (int) (queue.top() >>> 32);
          }
        }
        int collectCount = Math.max(0, queue.size() - off);
        int sortedIdxStart = queue.size() - (collectCount - 1);
        int sortedIdxEnd = queue.size() + 1;
        final long[] sorted = queue.sort(collectCount);
        for (int i = sortedIdxStart; i < sortedIdxEnd; i++) {
          long pair = sorted[i];
          int c = (int) (pair >>> 32);
          int tnum = Integer.MAX_VALUE - (int) pair;
          final BytesRef term = si.lookupOrd(tnum - 1);
          ft.indexedToReadable(term, charsRef);
          res.add(charsRef.toString(), c);
        }
      } else {
        // index order
        int i = 1;
        if (mincount <= 0) {
          i += off;
          off = 0;
        }
        for (; i < nTerms; i++) {
          int c = counts[i];
          if (c < mincount) continue;
          if (--off >= 0) continue;
          if (--lim < 0) break;
          ft.indexedToReadable(si.lookupOrd(i - 1), charsRef);
          res.add(charsRef.toString(), c);
        }
      }
    }
    return finish(res, schemaField, docSet, missingCount, missing);
  }

  private NamedList<Integer> finish(
      NamedList<Integer> res, SchemaField sf, DocSet docs, int missingCount, boolean missing)
      throws IOException {
    if (missing) {
      if (missingCount < 0) {
        missingCount = SimpleFacets.getFieldMissingCount(searcher, docs, sf.getName());
      }
      res.add(null, missingCount);
    }
    return res;
  }

  private static final class Entry {
    long bits;
    int count;
    int docID;
  }

  /** NumericFacets' single-valued point path (no zeros), counting natively. */
  private NamedList<Integer> numericCounts(
      SchemaField sf,
      FixedBitSet docs,
      int offset,
      int limit,
      int mincount,
      boolean missing,
      String sort)
      throws IOException {
    FieldType ft = sf.getType();
    boolean flip =
        ft.getNumberType() == org.apache.solr.schema.NumberType.FLOAT
            || ft.getNumberType() == org.apache.solr.schema.NumberType.DOUBLE;
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    // 1. accumulate: value bits -> {count, first doc}
    Map<Long, long[]> table = new HashMap<>();
    int missingCount = 0;
    for (LeafReaderContext leaf : leaves) {
      LuxirFacets.ValueCounts vc =
          LuxirFacets.valueCounts(leaf, sf.getName(), docs, LuxirFacetAccelerator.nearUniqueSample);
      if (vc == null) return null;
      missingCount += vc.missing();
      for (int i = 0; i < vc.values().length; i++) {
        long bits = vc.values()[i];
        // NumericFacets' order-preserving flip for float/double bits
        if (flip && bits < 0) bits ^= 0x7fffffffffffffffL;
        long[] e = table.get(bits);
        if (e == null) {
          table.put(bits, new long[] {vc.counts()[i], vc.firstDocs()[i]});
        } else {
          e[0] += vc.counts()[i];
          e[1] = Math.min(e[1], vc.firstDocs()[i]);
        }
      }
    }
    NamedList<Integer> result = new NamedList<>();
    if (limit == 0) {
      if (missing) result.add(null, missingCount);
      return result;
    }
    // 2. select top-k facet values
    int pqSize = limit < 0 ? table.size() : Math.min(offset + limit, table.size());
    PriorityQueue<Entry> pq;
    if (FacetParams.FACET_SORT_COUNT.equals(sort)
        || FacetParams.FACET_SORT_COUNT_LEGACY.equals(sort)) {
      pq =
          new PriorityQueue<>(pqSize) {
            @Override
            protected boolean lessThan(Entry a, Entry b) {
              return a.count < b.count || (a.count == b.count && a.bits > b.bits);
            }
          };
    } else {
      pq =
          new PriorityQueue<>(pqSize) {
            @Override
            protected boolean lessThan(Entry a, Entry b) {
              return a.bits > b.bits;
            }
          };
    }
    Entry e = null;
    for (Map.Entry<Long, long[]> t : table.entrySet()) {
      if (t.getValue()[0] >= mincount) {
        if (e == null) e = new Entry();
        e.bits = t.getKey();
        e.count = (int) t.getValue()[0];
        e.docID = (int) t.getValue()[1];
        e = pq.insertWithOverflow(e);
      }
    }
    // 3. render through the field's value source, as NumericFacets does
    ValueSource vs = ft.getValueSource(sf, null);
    Deque<Entry> counts = new ArrayDeque<>();
    while (pq.size() > offset) counts.addFirst(pq.pop());
    for (Entry entry : counts) {
      int readerIdx = org.apache.lucene.index.ReaderUtil.subIndex(entry.docID, leaves);
      FunctionValues values = vs.getValues(Map.of(), leaves.get(readerIdx));
      result.add(values.strVal(entry.docID - leaves.get(readerIdx).docBase), entry.count);
    }
    if (missing) result.add(null, missingCount);
    return result;
  }
}
