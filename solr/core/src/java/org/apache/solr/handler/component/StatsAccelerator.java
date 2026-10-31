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
package org.apache.solr.handler.component;

import java.io.IOException;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * Reads a single-valued field's doc values for the stats component in bulk: a codec that keeps doc
 * values natively can supply the values of a doc set at once instead of one doc at a time. Put in
 * the request context under {@link #CONTEXT_KEY}; {@link StatsField} then accumulates the values in
 * doc order, as it would have read them.
 *
 * @lucene.experimental
 */
public interface StatsAccelerator {
  String CONTEXT_KEY = StatsAccelerator.class.getName();

  /** Receives the docs of the set in order, each with its doc value or none. */
  interface ValueSink {
    /**
     * {@code doc} within the segment {@code ctx}; {@code value} as the field's NUMERIC doc values
     * hold it, or its SORTED ordinal in the segment; ignored when {@code present} is false.
     */
    void accept(LeafReaderContext ctx, int doc, boolean present, long value) throws IOException;
  }

  /**
   * Passes every doc of {@code docs} in doc order to {@code sink} with its value of the
   * single-valued {@code field}; returns false, before passing any, if it cannot.
   */
  boolean values(SolrIndexSearcher searcher, SchemaField field, DocSet docs, ValueSink sink)
      throws IOException;
}
