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
import java.util.List;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.handler.component.FacetComponent;
import org.apache.solr.handler.component.PivotFacetProcessor;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SpatialHeatmapFacets;
import org.apache.solr.request.SimpleFacets;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.QueryLimits;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.facet.FacetDebugInfo;
import org.apache.solr.util.RTimer;

/**
 * Solr's facet component with field and range facets on doc values kept by Luxir counted natively
 * ({@link LuxirSimpleFacets}, {@link LuxirRangeFacetProcessor}). Everything else, including
 * distributed faceting, is {@link FacetComponent}'s.
 *
 * <pre>
 * &lt;searchComponent name="facet" class="org.apache.solr.luxir.codec.LuxirFacetComponent"/&gt;
 * </pre>
 */
public class LuxirFacetComponent extends FacetComponent {

  @Override
  protected SimpleFacets newSimpleFacets(
      SolrQueryRequest req, DocSet docSet, SolrParams params, ResponseBuilder rb) {
    return new LuxirSimpleFacets(req, docSet, params, rb);
  }

  // FacetComponent.process, with LuxirRangeFacetProcessor for range facets
  @Override
  public void process(ResponseBuilder rb) throws IOException {
    if (!rb.doFacets) return;
    QueryLimits queryLimits = QueryLimits.getCurrentLimits();
    SolrParams params = rb.req.getParams();
    SimpleFacets f = newSimpleFacets(rb.req, rb.getResults().docSet, params, rb);
    RTimer timer = null;
    FacetDebugInfo fdebug = null;
    if (rb.isDebug()) {
      fdebug = new FacetDebugInfo();
      rb.req.getContext().put("FacetDebugInfo-nonJson", fdebug);
      timer = new RTimer();
    }
    NamedList<Object> counts = luxirFacetCounts(f, fdebug);
    rb.rsp.add(FACET_COUNTS_KEY, counts);
    if (queryLimits.maybeExitWithPartialResults("Faceting counts")) return;
    String[] pivots = params.getParams(FacetParams.FACET_PIVOT);
    if (pivots != null && pivots.length != 0) {
      PivotFacetProcessor pivotProcessor =
          new PivotFacetProcessor(rb.req, rb.getResults().docSet, params, rb);
      SimpleOrderedMap<List<NamedList<Object>>> v = pivotProcessor.process(pivots);
      if (v != null) counts.add("facet_pivot", v);
      if (queryLimits.maybeExitWithPartialResults("Faceting pivots")) return;
    }
    if (fdebug != null) fdebug.setElapse((long) timer.getTime());
  }

  // FacetComponent.getFacetCounts, with LuxirRangeFacetProcessor
  private static NamedList<Object> luxirFacetCounts(
      SimpleFacets simpleFacets, FacetDebugInfo fdebug) {
    if (!simpleFacets.getGlobalParams().getBool(FacetParams.FACET, true)) return null;
    LuxirRangeFacetProcessor rangeFacetProcessor =
        new LuxirRangeFacetProcessor(
            simpleFacets.getRequest(),
            simpleFacets.getDocsOrig(),
            simpleFacets.getGlobalParams(),
            simpleFacets.getResponseBuilder());
    NamedList<Object> counts = new SimpleOrderedMap<>();
    try {
      counts.add(FACET_QUERY_KEY, simpleFacets.getFacetQueryCounts());
      if (fdebug != null) {
        FacetDebugInfo fd = new FacetDebugInfo();
        fd.putInfoItem("action", "field facet");
        fd.setProcessor(simpleFacets.getClass().getSimpleName());
        fdebug.addChild(fd);
        simpleFacets.setFacetDebugInfo(fd);
        final RTimer timer = new RTimer();
        counts.add(FACET_FIELD_KEY, simpleFacets.getFacetFieldCounts());
        fd.setElapse((long) timer.getTime());
      } else {
        counts.add(FACET_FIELD_KEY, simpleFacets.getFacetFieldCounts());
      }
      counts.add(FACET_RANGES_KEY, rangeFacetProcessor.getFacetRangeCounts());
      counts.add(FACET_INTERVALS_KEY, simpleFacets.getFacetIntervalCounts());
      counts.add(SpatialHeatmapFacets.RESPONSE_KEY, simpleFacets.getHeatmapCounts());
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    } catch (SyntaxError e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }
    return counts;
  }

  @Override
  public String getDescription() {
    return "facets, with field and range facets on Luxir doc values counted by Luxir";
  }
}
