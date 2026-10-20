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
import org.apache.lucene.index.IndexableField;
import org.apache.solr.schema.FloatPointField;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;

/** A {@link FloatPointField} whose values Luxir indexes (see {@link LuxirValueType}). */
public class LuxirFloatPointField extends FloatPointField implements LuxirValueType {
  private IndexSchema schema;

  @Override
  protected void init(IndexSchema schema, Map<String, String> args) {
    super.init(schema, args);
    this.schema = schema;
  }

  @Override
  public List<IndexableField> createFields(SchemaField sf, Object value) {
    if (!LuxirValueType.nativeField(schema, sf)) return super.createFields(sf, value);
    Float v = value instanceof Number n ? n.floatValue() : Float.parseFloat(value.toString());
    return LuxirValueType.number(sf, 4, v);
  }
}
