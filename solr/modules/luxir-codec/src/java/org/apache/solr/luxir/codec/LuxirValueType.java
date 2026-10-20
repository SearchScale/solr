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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.luxir.lucene.document.LuxirValueField;

/**
 * A string or point field type whose values Luxir indexes: one {@link LuxirValueField} per value
 * replaces Solr's indexed, doc-values and stored fields, and the codec builds the postings, doc
 * values and points natively, so Lucene's indexing chain hashes and buffers nothing for it. Values
 * are indexed, stored and searched exactly as by the Solr type it extends.
 *
 * <p>Applies to indexed fields other than the uniqueKey and the nested-document fields (which
 * Lucene deletes by term); doc-values-only and stored-only fields (and in-place updates) take the
 * standard path. Indexed string values are limited to 255 UTF-8 bytes. Requires {@link
 * LuxirCodecFactory}.
 */
public interface LuxirValueType {
  /** Whether a field of a Luxir value type is indexed natively. */
  static boolean nativeField(IndexSchema schema, SchemaField sf) {
    if (!sf.indexed()) return false;
    String name = sf.getName();
    SchemaField key = schema == null ? null : schema.getUniqueKeyField();
    return (key == null || !key.getName().equals(name))
        && !name.equals(IndexSchema.ROOT_FIELD_NAME)
        && !name.equals(IndexSchema.NEST_PATH_FIELD_NAME);
  }

  /** The schema of native string fields, shared per shape. */
  static LuxirValueField.Type stringType(SchemaField sf) {
    DocValuesType dv =
        !sf.hasDocValues()
            ? DocValuesType.NONE
            : sf.multiValued() ? DocValuesType.SORTED_SET : DocValuesType.SORTED;
    return Types.TYPES.computeIfAbsent(
        "s" + dv + sf.stored(), k -> LuxirValueField.Type.string(true, dv, sf.stored()));
  }

  /** The schema of native numeric fields ({@code bytes} per point), shared per shape. */
  static LuxirValueField.Type numberType(SchemaField sf, int bytes) {
    DocValuesType dv =
        !sf.hasDocValues()
            ? DocValuesType.NONE
            : sf.multiValued() ? DocValuesType.SORTED_NUMERIC : DocValuesType.NUMERIC;
    return Types.TYPES.computeIfAbsent(
        "n" + bytes + dv + sf.stored(), k -> LuxirValueField.Type.number(bytes, dv, sf.stored()));
  }

  /** One native numeric value. */
  static List<IndexableField> number(SchemaField sf, int bytes, Number value) {
    return List.of(new LuxirValueField(sf.getName(), numberType(sf, bytes), value));
  }

  /** Cache holder (interfaces cannot have private static fields). */
  final class Types {
    static final Map<String, LuxirValueField.Type> TYPES = new ConcurrentHashMap<>();

    private Types() {}
  }
}
