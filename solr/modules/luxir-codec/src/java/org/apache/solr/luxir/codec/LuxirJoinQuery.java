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
import java.util.Objects;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.search.BitDocSet;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.DocSetProducer;
import org.apache.solr.search.JoinQParserPlugin;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrSearcherRequirer;
import org.luxir.lucene.search.LuxirJoin;

/**
 * A same-core join (Solr's {@code {!join from=... to=...}}) over single-valued string fields,
 * computed natively by {@link LuxirJoin}: the from query's docs come from Solr (and its filter
 * cache), their values are matched through the fields' doc-values dictionaries, and each segment's
 * column is scanned once. Where the fields are not Luxir doc-values columns in every segment, Solr's
 * own join runs instead. Matches the same docs as Solr's join; constant score.
 */
public class LuxirJoinQuery extends Query implements DocSetProducer, SolrSearcherRequirer {
  private final String fromField;
  private final String toField;
  private final Query fromQuery;

  public LuxirJoinQuery(String fromField, String toField, Query fromQuery) {
    this.fromField = fromField;
    this.toField = toField;
    this.fromQuery = fromQuery;
  }

  /** The join's docs, natively where possible, else by Solr's join. */
  @Override
  public DocSet createDocSet(SolrIndexSearcher searcher) throws IOException {
    FixedBitSet bits = nativeJoin(searcher);
    if (bits == null) return searcher.getDocSet(solrJoin());
    return new BitDocSet(bits);
  }

  private FixedBitSet nativeJoin(SolrIndexSearcher searcher) throws IOException {
    DocSet from = searcher.getDocSet(fromQuery);
    int maxDoc = searcher.maxDoc();
    if (from.size() == 0) return new FixedBitSet(Math.max(1, maxDoc));
    return LuxirJoin.join(
        searcher.getIndexReader(), fromField, toField, LuxirCodecQueryComponent.bits(from, maxDoc));
  }

  private Query solrJoin() {
    return JoinQParserPlugin.createJoinQuery(fromQuery, fromField, toField, null);
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    if (!(searcher instanceof SolrIndexSearcher solrSearcher)) {
      return searcher.rewrite(solrJoin()).createWeight(searcher, scoreMode, boost);
    }
    FixedBitSet bits = nativeJoin(solrSearcher);
    if (bits == null) {
      return searcher.rewrite(solrJoin()).createWeight(searcher, scoreMode, boost);
    }
    return new ConstantScoreWeight(this, boost) {
      @Override
      public ScorerSupplier scorerSupplier(LeafReaderContext context) {
        int base = context.docBase;
        int end = base + context.reader().maxDoc();
        if (bits.nextSetBit(base, end) == DocIdSetIterator.NO_MORE_DOCS) return null;
        DocIdSetIterator it = new SliceIterator(bits, base, end);
        return new DefaultScorerSupplier(new ConstantScoreScorer(score(), scoreMode, it));
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return false;
      }
    };
  }

  /** One segment's docs of an index-wide bitset. */
  private static final class SliceIterator extends DocIdSetIterator {
    private final FixedBitSet bits;
    private final int base;
    private final int end;
    private int doc = -1;

    SliceIterator(FixedBitSet bits, int base, int end) {
      this.bits = bits;
      this.base = base;
      this.end = end;
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) {
      int global = base + target;
      if (global >= end) return doc = NO_MORE_DOCS;
      int next = bits.nextSetBit(global, end);
      return doc = next == NO_MORE_DOCS ? NO_MORE_DOCS : next - base;
    }

    @Override
    public long cost() {
      return end - base;
    }
  }

  @Override
  public void visit(QueryVisitor visitor) {
    visitor.visitLeaf(this);
  }

  @Override
  public String toString(String field) {
    return "{!join from=" + fromField + " to=" + toField + "}" + fromQuery;
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other)
        && fromField.equals(((LuxirJoinQuery) other).fromField)
        && toField.equals(((LuxirJoinQuery) other).toField)
        && fromQuery.equals(((LuxirJoinQuery) other).fromQuery);
  }

  @Override
  public int hashCode() {
    return Objects.hash(classHash(), fromField, toField, fromQuery);
  }
}
