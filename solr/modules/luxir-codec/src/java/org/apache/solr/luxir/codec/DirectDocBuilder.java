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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.CopyField;
import org.apache.solr.schema.DenseVectorField;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessorChain;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

/**
 * Builds Lucene documents straight from parsed JSON values, without a {@code SolrInputDocument}:
 * per field name, the schema field and copy-field targets are looked up once per schema, and each
 * value goes through its field type's {@code createFields}, as Solr's {@code DocumentBuilder} does.
 * The command carries the built document and a {@code SolrInputDocument} holding only the
 * uniqueKey, for the processors that log or route by id.
 *
 * <p>Used only where nothing downstream reads the rest of the {@code SolrInputDocument}: no update
 * log, no SolrCloud, no nested documents, and an update chain of Solr's default processors (plus
 * {@link ParallelFlushUpdateProcessorFactory}). Any document the standard builder would treat
 * specially (an unknown field, several values for a single-valued field, a missing required field,
 * a field name repeated apart, dense vectors, values combined across a field) takes the standard
 * path, which also produces the standard errors.
 */
final class DirectDocBuilder {
  /** Update processors known to read only the uniqueKey of an add command's document. */
  private static final Set<String> SAFE_PROCESSORS =
      Set.of(
          "LogUpdateProcessorFactory",
          "DistributedUpdateProcessorFactory",
          "RunUpdateProcessorFactory",
          "NestedUpdateProcessorFactory",
          ParallelFlushUpdateProcessorFactory.class.getSimpleName());

  private static final int MIN_LENGTH_TO_MOVE_LAST =
      Integer.getInteger("solr.docBuilder.minLengthToMoveLast", 4 * 1024);

  /** How one field name is indexed; null schema field: not handled directly. */
  private record Plan(SchemaField field, CopyField[] copies) {}

  private static final Plan STANDARD = new Plan(null, null);

  /** Plans per schema instance (a schema change gives a new instance). */
  private static final Map<IndexSchema, DirectDocBuilder> BUILDERS =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  private final IndexSchema schema;
  private final SchemaField uniqueKey;
  private final List<SchemaField> required;
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();

  private DirectDocBuilder(IndexSchema schema) {
    this.schema = schema;
    this.uniqueKey = schema.getUniqueKeyField();
    this.required = List.copyOf(schema.getRequiredFields());
  }

  /** The builder for this request, or null when its documents must take the standard path. */
  static DirectDocBuilder forRequest(SolrQueryRequest req) {
    var core = req.getCore();
    if (core == null || core.getUpdateHandler().getUpdateLog() != null) return null;
    if (core.getCoreContainer().isZooKeeperAware()) return null;
    IndexSchema schema = req.getSchema();
    if (schema.getUniqueKeyField() == null || schema.isUsableForChildDocs()) return null;
    UpdateRequestProcessorChain chain = core.getUpdateProcessorChain(req.getParams());
    for (UpdateRequestProcessorFactory f : chain.getProcessors()) {
      if (!SAFE_PROCESSORS.contains(f.getClass().getSimpleName())) return null;
    }
    return BUILDERS.computeIfAbsent(schema, DirectDocBuilder::new);
  }

  private Plan plan(String name) {
    Plan plan = plans.get(name);
    if (plan == null) {
      plan = makePlan(name);
      plans.put(name, plan);
    }
    return plan;
  }

  private Plan makePlan(String name) {
    SchemaField sf = schema.getFieldOrNull(name);
    if (sf == null || sf.getType() instanceof DenseVectorField) return STANDARD;
    if (sf.multiValued() && sf.getType().shouldCreateFieldsFromAllValues()) return STANDARD;
    List<CopyField> copies = schema.getCopyFieldsList(name);
    for (CopyField cf : copies) {
      if (cf.getDestination().getType() instanceof DenseVectorField) return STANDARD;
    }
    return new Plan(sf, copies.toArray(new CopyField[0]));
  }

  /** A command carrying the built document, or null to take the standard path. */
  AddUpdateCommand command(SolrQueryRequest req, FastJsonDocsLoader.Parsed doc) {
    Document out;
    try {
      out = build(doc);
    } catch (RuntimeException e) {
      return null; // the standard path reports it
    }
    if (out == null) return null;
    Object id = null;
    String key = uniqueKey.getName();
    for (int i = 0; i < doc.size; i++) {
      if (doc.keys[i].equals(key)) {
        if (id != null) return null;
        id = doc.values[i];
      }
    }
    if (id == null) return null;
    SolrInputDocument idOnly = new SolrInputDocument();
    idOnly.addField(key, id);
    Command cmd = new Command(req, out);
    cmd.solrDoc = idOnly;
    return cmd;
  }

  private Document build(FastJsonDocsLoader.Parsed doc) {
    Document out = new Document();
    List<String> used = new ArrayList<>(doc.size + 4); // field names given values, in order
    String prev = null;
    Plan plan = null;
    for (int i = 0; i < doc.size; i++) {
      String name = doc.keys[i];
      if (!name.equals(prev)) {
        if (used.contains(name)) return null; // repeated apart, or also a copy target
        plan = plan(name);
        if (plan.field == null) return null;
        if (!plan.field.multiValued() && i + 1 < doc.size && name.equals(doc.keys[i + 1]))
          return null;
        prev = name;
        used.add(name);
      }
      Object v = doc.values[i];
      add(out, plan.field, v);
      for (CopyField cf : plan.copies) {
        SchemaField dest = cf.getDestination();
        boolean destHasValues = used.contains(dest.getName());
        if (!dest.multiValued() && destHasValues) return null;
        Object value = v;
        if (v instanceof CharSequence && cf.getMaxChars() > 0)
          value = cf.getLimitedValue(v.toString());
        add(out, dest, value);
        if (!destHasValues) used.add(dest.getName());
      }
    }
    for (SchemaField f : required) {
      if (out.getField(f.getName()) == null) return null;
    }
    moveLargestFieldLast(out);
    return out;
  }

  private static void add(Document out, SchemaField field, Object v) {
    if (v instanceof IndexableField f) {
      out.add(f);
      return;
    }
    for (IndexableField f : field.getType().createFields(field, v)) {
      if (f != null) out.add(f);
    }
  }

  /** As Solr's DocumentBuilder: the largest stored value last, so it can be skipped on reads. */
  private static void moveLargestFieldLast(Document doc) {
    String largestField = null;
    int largestFieldLen = -1;
    boolean largestIsLast = true;
    for (IndexableField field : doc) {
      if (!field.fieldType().stored()) continue;
      if (largestIsLast && !field.name().equals(largestField)) largestIsLast = false;
      if (field.numericValue() != null) continue;
      String strVal = field.stringValue();
      if (strVal != null) {
        if (strVal.length() > largestFieldLen) {
          largestField = field.name();
          largestFieldLen = strVal.length();
          largestIsLast = true;
        }
      } else {
        BytesRef bytesRef = field.binaryValue();
        if (bytesRef != null && bytesRef.length > largestFieldLen) {
          largestField = field.name();
          largestFieldLen = bytesRef.length;
          largestIsLast = true;
        }
      }
    }
    if (!largestIsLast && largestField != null && largestFieldLen > MIN_LENGTH_TO_MOVE_LAST) {
      List<IndexableField> addToEnd = new ArrayList<>();
      Iterator<IndexableField> iterator = doc.iterator();
      while (iterator.hasNext()) {
        IndexableField field = iterator.next();
        if (field.name().equals(largestField)) {
          addToEnd.add(field);
          iterator.remove();
        }
      }
      for (IndexableField field : addToEnd) doc.add(field);
    }
  }

  /** An add command whose Lucene document is already built. */
  static final class Command extends AddUpdateCommand {
    private final Document doc;

    Command(SolrQueryRequest req, Document doc) {
      super(req);
      this.doc = doc;
    }

    @Override
    protected Iterable<Document> makeLuceneDocs() {
      return List.of(doc);
    }
  }
}
