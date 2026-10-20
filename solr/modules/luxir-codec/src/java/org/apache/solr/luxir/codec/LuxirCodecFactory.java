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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CodecFactory;
import org.apache.solr.core.SchemaCodecFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.luxir.lucene.codec.LuxirCodec;

/**
 * Solr's schema codec with Luxir doing the indexing: fields of type {@link LuxirTextField} are
 * analyzed and inverted by Luxir, fields of a {@link LuxirValueType} (strings, points) are indexed
 * by Luxir, fields with {@code postingsFormat="Luxir"} keep Lucene's analysis but Luxir's postings,
 * and everything else (per-field postings formats, compression mode) is {@link
 * SchemaCodecFactory}'s.
 *
 * <pre>&lt;codecFactory class="org.apache.solr.luxir.codec.LuxirCodecFactory"/&gt;</pre>
 */
public class LuxirCodecFactory extends CodecFactory implements SolrCoreAware {
  private final SchemaCodecFactory schemaCodec = new SchemaCodecFactory();
  private final AtomicReference<Map.Entry<IndexSchema, String>> nativeSchema =
      new AtomicReference<>();
  private SolrCore core;
  private Codec codec;

  @Override
  public void init(NamedList<?> args) {
    super.init(args);
    schemaCodec.init(args);
  }

  @Override
  public void inform(SolrCore core) {
    this.core = core;
    schemaCodec.inform(core);
    Codec delegate = schemaCodec.getCodec();
    codec = new LuxirCodec(delegate, this::postingsFormatFor, this::nativeSchemaJson);
  }

  private PostingsFormat postingsFormatFor(String field) {
    SchemaField sf = core.getLatestSchema().getFieldOrNull(field);
    if (sf != null
        && (sf.getType() instanceof LuxirTextField
            || sf.getType() instanceof LuxirValueType
            || "Luxir".equals(sf.getType().getPostingsFormat()))) {
      return null; // Luxir
    }
    Codec delegate = schemaCodec.getCodec();
    return delegate instanceof Lucene104Codec c
        ? c.getPostingsFormatForField(field)
        : delegate.postingsFormat();
  }

  /** The Luxir SchemaDef of LuxirTextField fields and suffix dynamic fields, per schema version. */
  private String nativeSchemaJson() {
    IndexSchema schema = core.getLatestSchema();
    Map.Entry<IndexSchema, String> cached = nativeSchema.get();
    if (cached != null && cached.getKey() == schema) return cached.getValue();
    StringBuilder fields = new StringBuilder(), templates = new StringBuilder();
    for (SchemaField f : schema.getFields().values()) {
      // names Luxir's schema cannot hold (such as _text_) go by their native schema name
      if (f.getType() instanceof LuxirTextField t) {
        append(fields, org.luxir.lucene.document.LuxirTextField.schemaName(f.getName()), t);
      }
    }
    for (IndexSchema.DynamicField df : schema.getDynamicFields()) {
      if (!(df.getPrototype().getType() instanceof LuxirTextField t)) continue;
      String pattern = df.getRegex();
      if (!pattern.startsWith("*") || pattern.indexOf('*', 1) >= 0 || !pattern.startsWith("*_")) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "LuxirTextField dynamic fields must be suffix patterns like *_lx: " + pattern);
      }
      append(templates, pattern.substring(1), t);
    }
    String json = "{\"fields\":{" + fields + "},\"templates\":{" + templates + "}}";
    nativeSchema.set(Map.entry(schema, json));
    return json;
  }

  private static void append(StringBuilder sb, String name, LuxirTextField type) {
    if (sb.length() > 0) sb.append(',');
    sb.append('"')
        .append(name.replace("\\", "\\\\").replace("\"", "\\\""))
        .append("\":{\"type\":\"text\",\"multi\":true,\"stored\":false,\"analyzer\":")
        .append(type.analyzerJson())
        .append('}');
  }

  @Override
  public Codec getCodec() {
    return codec;
  }
}
