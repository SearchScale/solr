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
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.handler.component.StatsAccelerator;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.luxir.lucene.search.LuxirFacets;

/**
 * The stats component's values of a single-valued field whose doc values every segment keeps
 * natively, read in batches of a segment's matches instead of a doc at a time.
 */
final class LuxirStatsAccelerator implements StatsAccelerator {
  static final LuxirStatsAccelerator INSTANCE = new LuxirStatsAccelerator();

  /** Fields whose stats were read natively (read by tests). */
  static final AtomicLong ACCELERATED = new AtomicLong();

  /**
   * Doc sets smaller than this are left to Solr's per-doc reading: bulk reading costs a pass over
   * every segment's bits, and pivot stats ask for one small set per bucket.
   */
  static int minDocs = 4096;

  private LuxirStatsAccelerator() {}

  @Override
  public boolean values(SolrIndexSearcher searcher, SchemaField field, DocSet docs, ValueSink sink)
      throws IOException {
    DocValuesType type =
        field.getType().getNumberType() == null ? DocValuesType.SORTED : DocValuesType.NUMERIC;
    if (docs.size() < minDocs
        || field.multiValued()
        || !LuxirFacets.nativeField(searcher, field.getName(), type)) {
      return false;
    }
    FixedBitSet bits = LuxirCodecQueryComponent.bits(docs, searcher.maxDoc());
    for (LeafReaderContext leaf : searcher.getTopReaderContext().leaves()) {
      if (!LuxirFacets.docValues(
          leaf,
          field.getName(),
          bits,
          (doc, present, value) -> sink.accept(leaf, doc, present, value))) {
        throw new IllegalStateException("not native: " + field.getName()); // checked above
      }
    }
    ACCELERATED.incrementAndGet();
    return true;
  }
}
