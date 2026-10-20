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
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexableField;
import org.apache.solr.common.util.ByteArrayUtf8CharSequence;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TextField;
import org.luxir.lucene.analysis.LuxirAnalyzer;

/**
 * A text field type that Luxir indexes: Luxir's native analyzer and inverter build the field's
 * postings and norms inside the codec, instead of Lucene's analysis chain and in-memory inverter.
 * Queries, highlighting and multi-term (prefix, wildcard) text use {@link LuxirAnalyzer} over the
 * same chain, so indexed and query terms always agree.
 *
 * <pre>
 * &lt;fieldType name="text_lx" class="org.apache.solr.luxir.codec.LuxirTextField"
 *     luxirAnalyzer='{"tokenizer":"unicode_word","filters":["nfkc_cf","fold","kstem"]}'/&gt;
 * </pre>
 *
 * The analyzer is a Luxir AnalyzerDef (default {@link LuxirAnalyzer#ENGLISH}). Requires {@link
 * LuxirCodecFactory}. Dynamic fields of this type must be suffix patterns ({@code *_xx}).
 */
public class LuxirTextField extends TextField {
  public static final String ANALYZER_ARG = "luxirAnalyzer";

  private String analyzerJson;

  @Override
  protected void init(IndexSchema schema, Map<String, String> args) {
    String json = args.remove(ANALYZER_ARG);
    analyzerJson = json == null ? LuxirAnalyzer.ENGLISH : json;
    postingsFormat = "Luxir";
    super.init(schema, args);
    LuxirAnalyzer analyzer = new LuxirAnalyzer(analyzerJson);
    setIndexAnalyzer(analyzer);
    setQueryAnalyzer(analyzer);
    setMultiTermAnalyzer(multiTermAnalyzer(analyzer));
  }

  /**
   * The whole multi-term text (a wildcard or prefix pattern) as one token, normalized by the
   * chain's normalizing filters (case and accent folding, no stemming), as Solr's multi-term
   * analyzers do.
   */
  static Analyzer multiTermAnalyzer(LuxirAnalyzer analyzer) {
    return new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        KeywordTokenizer source = new KeywordTokenizer();
        TokenStream normalized =
            new TokenFilter(source) {
              private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

              @Override
              public boolean incrementToken() throws IOException {
                if (!input.incrementToken()) return false;
                String n = analyzer.normalizeTerm(term.toString());
                term.setEmpty().append(n);
                return true;
              }
            };
        return new TokenStreamComponents(source, normalized);
      }
    };
  }

  /** The Luxir AnalyzerDef (JSON) that indexes and queries this type. */
  public String analyzerJson() {
    return analyzerJson;
  }

  @Override
  public IndexableField createField(SchemaField field, Object value) {
    if (!field.indexed()) return super.createField(field, value);
    if (value instanceof ByteArrayUtf8CharSequence u) {
      // UTF-8 straight from the request (FastJsonDocsLoader): no String, no re-encoding
      return new org.luxir.lucene.document.LuxirTextField(
          field.getName(), u.getBuf(), u.offset(), u.size(), field.stored());
    }
    String text = value == null ? null : value.toString();
    if (text == null) return null;
    return new org.luxir.lucene.document.LuxirTextField(field.getName(), text, field.stored());
  }
}
