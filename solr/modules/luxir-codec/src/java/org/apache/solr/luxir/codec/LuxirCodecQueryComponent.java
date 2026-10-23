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
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.QueryComponent;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.response.BasicResultContext;
import org.apache.solr.search.BitDocSet;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.ExtendedQuery;
import org.apache.solr.search.PostFilter;
import org.apache.solr.search.QueryCommand;
import org.apache.solr.search.QueryResult;
import org.apache.solr.search.QueryResultKey;
import org.apache.solr.search.QueryUtils;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.WrappedQuery;
import org.apache.solr.search.stats.LocalStatsCache;
import org.apache.solr.uninverting.UninvertingReader;
import org.luxir.lucene.search.LuxirAcceleratedQuery;
import org.luxir.lucene.search.LuxirSearch;

/**
 * Solr's query component, with relevance-ranked searches over Luxir-format fields answered by
 * Luxir: one native call over all segments returns the top documents and the exact hit count (and,
 * for faceting, the matching document set). Filter queries go through Solr's filter cache as usual
 * and reach the native call as a bitset; when one is not cached yet, Luxir computes its doc set if
 * it can evaluate it (terms, point ranges, match-all and booleans of them). Uncached ones ({@code
 * cache=false}) run in the same native call where they can. Everything about the request and
 * response stays Solr's.
 *
 * <p>A sort by one field with native doc values (numbers, dates, single-valued strings) runs
 * natively too: Luxir finds the matches, then each segment's best documents by the field's column.
 *
 * <p>Requests the native path does not cover (other sorts, grouping, cursors, post filters, rank
 * queries, time limits, distributed statistics, or query shapes the native engine does not execute)
 * run through Solr's normal path. There the query is wrapped in {@link LuxirAcceleratedQuery}, so
 * top-level scoring still runs natively where Lucene's collector model allows; the remaining
 * iteration reads the same Luxir postings through the codec.
 *
 * <pre>
 * &lt;searchComponent name="query" class="org.apache.solr.luxir.codec.LuxirCodecQueryComponent"/&gt;
 * </pre>
 *
 * The postings themselves are selected per field type in the schema: {@code
 * postingsFormat="Luxir"}.
 */
public class LuxirCodecQueryComponent extends QueryComponent {
  public static final String COMPONENT_NAME = "luxirCodec";

  static {
    // Solr wraps segments to uninvert fields into doc values; terms and postings are untouched.
    LuxirSearch.registerTransparentWrapper(UninvertingReader.class);
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    if (rb.getQuery() instanceof MatchAllDocsQuery && fieldSort(rb) == null) {
      // Solr answers match-all from its cached live docs (and filters from the filter cache);
      // sorted by a field, it reads every doc's value, which Luxir's top-k does natively
      super.process(rb);
      return;
    }
    boolean enabled = rb.req.getParams().getBool(COMPONENT_NAME, true);
    if (enabled && processNatively(rb)) return;
    Query q = rb.getQuery();
    if (q == null || !enabled) {
      super.process(rb);
      return;
    }
    rb.setQuery(new LuxirAcceleratedQuery(q));
    try {
      super.process(rb);
    } finally {
      rb.setQuery(q);
    }
  }

  /** Why the request cannot run natively, or null if it can. */
  private static String ineligible(ResponseBuilder rb) {
    SolrParams params = rb.req.getParams();
    if (rb.getQuery() == null) return "no query";
    if (rb.getGroupingSpec() != null) return "grouping";
    if (rb.getRankQuery() != null) return "rank query";
    if (rb.getCursorMark() != null || params.get(CursorMarkParams.CURSOR_MARK_PARAM) != null)
      return "cursorMark";
    if (params.get(ShardParams.IDS) != null) return "search by ids";
    if (params.get(CommonParams.TIME_ALLOWED) != null
        || params.get(CommonParams.CPU_ALLOWED) != null
        || params.get(CommonParams.MEM_ALLOWED) != null) {
      return "query limits";
    }
    if (params.getBool(CommonParams.SEGMENT_TERMINATE_EARLY, false)) return "segmentTerminateEarly";
    if (params.getBool(CommonParams.MULTI_THREADED, false)) return "multiThreaded";
    if (!rb.req.getSearcher().getStatsCache().getClass().equals(LocalStatsCache.class)) {
      return "distributed statistics";
    }
    SortField fieldSort = fieldSort(rb);
    if (fieldSort == null && rb.getSortSpec() != null && rb.getSortSpec().getSort() != null) {
      SortField[] fields = rb.getSortSpec().getSort().getSort();
      if (fields.length != 1
          || fields[0].getType() != SortField.Type.SCORE
          || fields[0].getReverse()) {
        return "sort";
      }
    }
    if (fieldSort != null) {
      if ((rb.getFieldFlags() & SolrIndexSearcher.GET_SCORES) != 0) return "sort with scores";
      if (!LuxirSearch.sortSupported(rb.req.getSearcher(), fieldSort))
        return "sort field not native";
    }
    List<Query> filters = rb.getFilters();
    if (filters != null) {
      for (Query f : filters) {
        if (f instanceof PostFilter
            && f instanceof ExtendedQuery eq
            && !eq.getCache()
            && eq.getCost() >= 100) {
          return "post filter";
        }
      }
    }
    return null;
  }

  /** The request's one field sort (not by score), or null. */
  private static SortField fieldSort(ResponseBuilder rb) {
    SortSpec spec = rb.getSortSpec();
    if (spec == null || spec.getSort() == null) return null;
    SortField[] fields = spec.getSort().getSort();
    return fields.length == 1 && fields[0].getType() != SortField.Type.SCORE ? fields[0] : null;
  }

  private static void debugPath(ResponseBuilder rb, String path, String reason) {
    if (!rb.isDebug()) return;
    NamedList<Object> debug = new NamedList<>();
    debug.add("path", path);
    if (reason != null) debug.add("reason", reason);
    rb.addDebugInfo("luxirCodec", debug);
  }

  private boolean processNatively(ResponseBuilder rb) throws IOException {
    String reason = ineligible(rb);
    if (reason != null) {
      debugPath(rb, "lucene", reason);
      return false;
    }
    SolrIndexSearcher searcher = rb.req.getSearcher();
    QueryCommand cmd = rb.createQueryCommand();
    int offset = cmd.getOffset();
    int len = cmd.getLen();
    int k = (int) Math.min((long) offset + len, searcher.maxDoc());
    boolean needDocSet = rb.isNeedDocSet();
    long minExact = rb.req.getParams().getLong(CommonParams.MIN_EXACT_COUNT, Integer.MAX_VALUE);
    cmd.setMinExactCount(
        minExact < 0 || minExact > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) minExact);
    cmd.setDistribStatsDisabled(rb.isDistribStatsDisabled());

    // Solr's query result cache, keyed and windowed as SolrIndexSearcher does: either path
    // answers a repeated request from an entry the other computed.
    QueryResultKey key = resultKey(searcher, cmd);
    if (key != null) {
      int flags = cmd.getFlags();
      if (!needDocSet && (flags & SolrIndexSearcher.NO_CHECK_QCACHE) == 0) {
        DocList superset = searcher.getQueryResultCache().get(key);
        if (superset != null
            && ((flags & SolrIndexSearcher.GET_SCORES) == 0 || superset.hasScores())) {
          DocList page = superset.subset(offset, len);
          if (page != null) {
            QueryResult result = new QueryResult();
            result.setDocList(page);
            respond(rb, searcher, result, page);
            debugPath(rb, "cache", null);
            return true;
          }
        }
      }
      int window = searcher.getCore().getSolrConfig().queryResultWindowSize;
      long superset = Math.max(window, ((long) Math.max(k, 1) - 1) / window * window + window);
      k = (int) Math.min(superset, searcher.maxDoc());
    }

    // Cached filters (Solr's default) come from Solr's filter cache, as without this component;
    // Luxir computes the doc sets it lacks, where it can, and caches them under Solr's key.
    // Uncached filters run natively with the query, or by Solr if Luxir cannot evaluate them.
    List<Query> nativeFilters = new ArrayList<>();
    List<Query> solrFilters = new ArrayList<>();
    DocSet cached = null; // the cached filters' doc sets combined, as getProcessedFilter does
    int positiveSets = 0;
    boolean anyNegative = false;
    List<Query> filters = cmd.getFilterList();
    if (filters != null) {
      SolrCache<Query, DocSet> cache = searcher.getFilterCache();
      List<DocSet> negative = new ArrayList<>();
      for (Query f : filters) {
        if (cache != null && !(f instanceof ExtendedQuery eq && !eq.getCache())) {
          // SolrIndexSearcher's key: the positive query
          Query abs = QueryUtils.getAbs(f);
          Query cacheKey = abs instanceof WrappedQuery w ? w.getWrappedQuery() : abs;
          DocSet set =
              cacheKey instanceof MatchAllDocsQuery
                  ? searcher.getLiveDocSet()
                  : cache.computeIfAbsent(cacheKey, q -> filterDocSet(searcher, q));
          if (!f.equals(abs)) {
            negative.add(set);
          } else {
            cached = cached == null ? set : cached.intersection(set);
            positiveSets++;
          }
          continue;
        }
        Query abs = QueryUtils.makeQueryable(f);
        if (LuxirSearch.nativeFilter(searcher, abs)) {
          nativeFilters.add(abs);
        } else {
          solrFilters.add(f);
        }
      }
      anyNegative = !negative.isEmpty();
      if (!negative.isEmpty() && cached == null) cached = searcher.getLiveDocSet();
      for (DocSet set : negative) cached = cached.andNot(set);
    }
    DocSet solrSet = solrFilters.isEmpty() ? null : searcher.getDocSet(solrFilters);
    if (cached != null) solrSet = solrSet == null ? cached : solrSet.intersection(cached);
    FixedBitSet filter = solrSet == null ? null : bits(solrSet, searcher.maxDoc());
    // one cached doc set's own bits (not a combination made for this request): Luxir keeps its
    // native copy for the requests that follow
    boolean filterCached =
        filter != null
            && solrSet == cached
            && positiveSets == 1
            && !anyNegative
            && solrSet instanceof BitDocSet bds
            && bds.getBits() == filter;

    // minExactCount: count exactly up to max(minExactCount, rows), as Lucene's collectors do.
    int minExactCount = needDocSet ? Integer.MAX_VALUE : cmd.getMinExactCount();
    SortField sort = fieldSort(rb);
    LuxirSearch.Result res =
        sort != null
            ? LuxirSearch.topKSorted(
                searcher, cmd.getQuery(), nativeFilters, sort, k, filter, filterCached, needDocSet)
            : LuxirSearch.topK(
                searcher,
                cmd.getQuery(),
                nativeFilters,
                k,
                minExactCount,
                filter,
                filterCached,
                needDocSet);
    if (res == null) {
      if (rb.isDebug()) {
        debugPath(rb, "lucene", LuxirSearch.whyNotNative(searcher, cmd.getQuery(), nativeFilters));
      }
      return false;
    }

    boolean needScores = sort == null && (cmd.getFlags() & SolrIndexSearcher.GET_SCORES) != 0;
    float maxScore = needScores ? (res.totalHits() > 0 ? res.maxScore() : 0f) : Float.NaN;
    DocList all =
        new DocSlice(
            0,
            res.docs().length,
            res.docs(),
            sort == null ? res.scores() : null,
            res.totalHits(),
            maxScore,
            res.exactCount()
                ? TotalHits.Relation.EQUAL_TO
                : TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO);
    if (key != null && all.size() <= searcher.getCore().getSolrConfig().queryResultMaxDocsCached) {
      searcher.getQueryResultCache().put(key, all);
    }
    DocList page = all.subset(offset, len);
    QueryResult result = new QueryResult();
    if (needDocSet) {
      DocListAndSet listAndSet = new DocListAndSet();
      listAndSet.docList = page;
      listAndSet.docSet = new BitDocSet(res.matches(), (int) res.totalHits());
      result.setDocListAndSet(listAndSet);
    } else {
      result.setDocList(page);
    }
    respond(rb, searcher, result, page);
    debugPath(rb, "native", solrFilters.isEmpty() ? null : solrFilters.size() + " filters by Solr");
    return true;
  }

  /**
   * The query result cache key for the command, or null if the result is not to be cached (no
   * cache, or a query marked cache=false). Flags are not part of the key.
   */
  private static QueryResultKey resultKey(SolrIndexSearcher searcher, QueryCommand cmd) {
    if (searcher.getQueryResultCache() == null) return null;
    if (cmd.getQuery() instanceof ExtendedQuery eq && !eq.getCache()) return null;
    return new QueryResultKey(
        cmd.getQuery(),
        cmd.getFilterList(),
        cmd.getSort(),
        cmd.getFlags(),
        cmd.getMinExactCount(),
        cmd.isDistribStatsDisabled());
  }

  private void respond(
      ResponseBuilder rb, SolrIndexSearcher searcher, QueryResult result, DocList page)
      throws IOException {
    rb.setResult(result);
    rb.rsp.addResponse(new BasicResultContext(rb));
    rb.rsp.getToLog().add("hits", page.matches());
    if (rb.mergeFieldHandler != null) {
      rb.mergeFieldHandler.handleMergeFields(rb, searcher);
    } else {
      doFieldSortValues(rb, searcher);
    }
    doPrefetch(rb);
  }

  /**
   * The docs matching filter {@code q} as Solr's filter cache holds them (a sorted int set when
   * small): computed natively where Luxir can evaluate it, else by Solr.
   */
  private static DocSet filterDocSet(SolrIndexSearcher searcher, Query q) throws IOException {
    LuxirSearch.Result res =
        LuxirSearch.topK(
            searcher, new ConstantScoreQuery(q), List.of(), 0, Integer.MAX_VALUE, null, true);
    if (res == null) {
      WrappedQuery uncached = new WrappedQuery(q);
      uncached.setCache(false);
      return searcher.getDocSet(uncached);
    }
    // Kept as bits even when small: the native path takes a filter as bits, and a cached BitDocSet's
    // own bits let Luxir keep its native copy across requests (a SortedIntDocSet would be expanded
    // into new bits on every request).
    return new BitDocSet(res.matches(), (int) res.totalHits());
  }

  static FixedBitSet bits(DocSet set, int maxDoc) {
    // only a BitDocSet's bits are free: other sets build theirs (a SortedIntDocSet hashes every doc)
    if (set instanceof BitDocSet bds && bds.getBits() instanceof FixedBitSet f && f.length() >= maxDoc) {
      return f;
    }
    FixedBitSet f = new FixedBitSet(Math.max(1, maxDoc));
    set.addAllTo(f);
    return f;
  }

  @Override
  public String getDescription() {
    return "query, answered by Luxir for Luxir-format fields";
  }
}
