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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.NumericUtils;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.component.RangeFacetProcessor;
import org.apache.solr.handler.component.RangeFacetRequest;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.NumberType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SyntaxError;
import org.luxir.lucene.LxlNative;
import org.luxir.lucene.search.LuxirFacets;

/**
 * {@link RangeFacetProcessor} whose range counts on point fields with doc values kept by Luxir are
 * computed natively: all of a facet's ranges (including before/after/between) in one scan of the
 * field's column per segment over the matching documents. The ranges are the ones Solr would query,
 * so counts are the same; other fields and grouped facets take Solr's path.
 */
public class LuxirRangeFacetProcessor extends RangeFacetProcessor {
  private RangeFacetRequest countedFor;
  private DocSet countedDocs;
  private Map<RangeFacetRequest.FacetRange, Integer> counted;

  public LuxirRangeFacetProcessor(
      SolrQueryRequest req, DocSet docs, SolrParams params, ResponseBuilder rb) {
    super(req, docs, params, rb);
  }

  @Override
  protected int rangeCount(DocSet subset, RangeFacetRequest rfr, RangeFacetRequest.FacetRange fr)
      throws IOException, SyntaxError {
    if (!rfr.isGroupFacet()) {
      if (countedFor != rfr || countedDocs != subset) {
        counted = nativeCounts(subset, rfr);
        countedFor = rfr;
        countedDocs = subset;
      }
      Integer c = counted == null ? null : counted.get(fr);
      if (c != null) return c;
    }
    return super.rangeCount(subset, rfr, fr);
  }

  private Map<RangeFacetRequest.FacetRange, Integer> nativeCounts(
      DocSet subset, RangeFacetRequest rfr) throws IOException {
    SchemaField sf = rfr.getSchemaField();
    FieldType ft = sf.getType();
    NumberType type = ft.getNumberType();
    if (!ft.isPointField() || !sf.hasDocValues() || type == null) return null;
    DocValuesType dv = sf.multiValued() ? DocValuesType.SORTED_NUMERIC : DocValuesType.NUMERIC;
    if (!LuxirFacets.nativeField(searcher, sf.getName(), dv)) return null;
    List<RangeFacetRequest.FacetRange> ranges = rfr.getFacetRanges();
    Query[] queries = new Query[ranges.size()];
    for (int i = 0; i < ranges.size(); i++) {
      RangeFacetRequest.FacetRange fr = ranges.get(i);
      queries[i] = ft.getRangeQuery(null, sf, fr.lower, fr.upper, fr.includeLower, fr.includeUpper);
    }
    long[][] bounds = bounds(sf, queries);
    if (bounds == null) return null;
    int[] counts =
        LuxirFacets.rangeCounts(
            searcher,
            sf.getName(),
            kind(sf),
            LuxirCodecQueryComponent.bits(subset, searcher.maxDoc()),
            bounds[0],
            bounds[1]);
    if (counts == null) return null;
    Map<RangeFacetRequest.FacetRange, Integer> out = new IdentityHashMap<>();
    for (int i = 0; i < ranges.size(); i++) out.put(ranges.get(i), counts[i]);
    return out;
  }

  /**
   * How the point field's doc values column orders: single-valued floats/doubles keep raw bits;
   * multi-valued ones (and all others) sortable longs.
   */
  static int kind(SchemaField sf) {
    if (sf.multiValued()) return LxlNative.SORT_LONG;
    return switch (sf.getType().getNumberType()) {
      case FLOAT -> LxlNative.SORT_FLOAT_BITS;
      case DOUBLE -> LxlNative.SORT_DOUBLE_BITS;
      default -> LxlNative.SORT_LONG;
    };
  }

  /**
   * The inclusive sort-key bounds ({@code [los, his]}) of the point field's range queries, as Solr
   * built them; null if one is not a plain point range.
   */
  static long[][] bounds(SchemaField sf, Query[] queries) {
    NumberType type = sf.getType().getNumberType();
    long[] los = new long[queries.length], his = new long[queries.length];
    for (int i = 0; i < queries.length; i++) {
      Query q = queries[i];
      if (q instanceof IndexOrDocValuesQuery iodv) q = iodv.getIndexQuery();
      if (q instanceof MatchNoDocsQuery) {
        los[i] = 1;
        his[i] = 0;
      } else if (q instanceof PointRangeQuery pr && pr.getNumDims() == 1) {
        los[i] = key(type, pr.getLowerPoint());
        his[i] = key(type, pr.getUpperPoint());
      } else {
        return null;
      }
    }
    return new long[][] {los, his};
  }

  /** A packed point bound as the column's sort key (Float.compare/Double.compare order). */
  private static long key(NumberType type, byte[] packed) {
    return switch (type) {
      case INTEGER -> IntPoint.decodeDimension(packed, 0);
      case LONG, DATE -> LongPoint.decodeDimension(packed, 0);
      case FLOAT -> NumericUtils.floatToSortableInt(FloatPoint.decodeDimension(packed, 0));
      case DOUBLE -> NumericUtils.doubleToSortableLong(DoublePoint.decodeDimension(packed, 0));
    };
  }
}
