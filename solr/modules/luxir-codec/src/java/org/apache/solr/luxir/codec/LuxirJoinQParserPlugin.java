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

import org.apache.lucene.search.Query;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.StrField;
import org.apache.solr.search.JoinQParserPlugin;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SyntaxError;

/**
 * Solr's join query parser, with same-core joins between single-valued string fields that have doc
 * values answered by {@link LuxirJoinQuery}. Everything else (cross-core joins, an explicit {@code
 * method}, scoring joins, other field types) goes to Solr's join unchanged. Registered in place of
 * Solr's:
 *
 * <pre>
 * &lt;queryParser name="join" class="org.apache.solr.luxir.codec.LuxirJoinQParserPlugin"/&gt;
 * </pre>
 */
public class LuxirJoinQParserPlugin extends JoinQParserPlugin {
  @Override
  public QParser createParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    QParser solr = super.createParser(qstr, localParams, params, req);
    return new QParser(qstr, localParams, params, req) {
      @Override
      public Query parse() throws SyntaxError {
        String from = localParams == null ? null : localParams.get("from");
        String to = localParams == null ? null : localParams.get("to");
        if (from == null
            || to == null
            || localParams.get("fromIndex") != null
            || localParams.get("method") != null
            || localParams.get("score") != null
            || !nativeField(from)
            || !nativeField(to)) {
          return solr.getQuery();
        }
        QParser fromParser = subQuery(localParams.get(QueryParsing.V), null);
        fromParser.setIsFilter(true);
        return new LuxirJoinQuery(from, to, fromParser.getQuery());
      }

      private boolean nativeField(String name) {
        SchemaField f = req.getSchema().getFieldOrNull(name);
        // indexed too: Solr's join matches through the fields' terms, which doc values mirror
        return f != null
            && f.getType() instanceof StrField
            && f.indexed()
            && f.hasDocValues()
            && !f.multiValued();
      }
    };
  }
}
