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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.ParsePosition;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.processor.FieldMutatingUpdateProcessor.FieldNameSelector;
import org.apache.solr.update.processor.FieldMutatingUpdateProcessorFactory;
import org.apache.solr.update.processor.FieldNameMutatingUpdateProcessorFactory;
import org.apache.solr.update.processor.LogUpdateProcessorFactory;
import org.apache.solr.update.processor.ParseDateFieldUpdateProcessorFactory;
import org.apache.solr.update.processor.RemoveBlankFieldUpdateProcessorFactory;
import org.apache.solr.update.processor.UUIDUpdateProcessorFactory;
import org.apache.solr.update.processor.UpdateRequestProcessorChain;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

/**
 * Parses the date strings of {@link LuxirDatePointField} fields as they are loaded, giving the
 * {@link java.util.Date} that the request's {@link ParseDateFieldUpdateProcessorFactory} would
 * give: that processor then passes them through. It tries its formats in order, and each format
 * that does not match throws an exception, which costs far more than the parse: a value matching
 * its 8th format pays for 7 exceptions. Here each shape of value (the string with its digits
 * masked) is matched against the formats once, with the processor's own code, and its later values
 * are parsed with the first format that matched, which therefore throws nothing.
 *
 * <p>Applies only where the processor would parse the field's values as they arrive: the chain has
 * one parse-date processor, which selects the field, and every processor before it is one known to
 * leave the field's non-blank strings alone (field-name mutation that keeps the name, blank
 * removal, a UUID for the uniqueKey, logging, or a field mutator that does not select the field). A
 * value the chosen format does not parse, or of an unusual shape, is left to the processor.
 */
final class LuxirDateValues {
  /** Distinct shapes remembered per field set; later new shapes are left to the processor. */
  private static final int MAX_SHAPES = 1024;

  private static final int MAX_LENGTH = 64;

  /** Values parsed here (for tests and monitoring). */
  static final java.util.concurrent.atomic.LongAdder PARSED =
      new java.util.concurrent.atomic.LongAdder();

  /** Per chain instance (a request's own processor list gives a new one). */
  private static final Map<UpdateRequestProcessorChain, LuxirDateValues> BY_CHAIN =
      Collections.synchronizedMap(new WeakHashMap<>());

  private static final LuxirDateValues NONE = new LuxirDateValues(null, null, null, null, null);

  private final IndexSchema schema;
  private final List<DateTimeFormatter> formats;
  private final FieldNameSelector parseDate;
  private final List<FieldNameSelector> before; // mutators ahead of parse-date
  private final List<Rename> renames; // field-name mutators ahead of parse-date
  private final Map<String, Boolean> fields = new ConcurrentHashMap<>();
  private final Map<String, Integer> shapes = new ConcurrentHashMap<>();

  private record Rename(Pattern pattern, String replacement) {}

  private LuxirDateValues(
      IndexSchema schema,
      List<DateTimeFormatter> formats,
      FieldNameSelector parseDate,
      List<FieldNameSelector> before,
      List<Rename> renames) {
    this.schema = schema;
    this.formats = formats;
    this.parseDate = parseDate;
    this.before = before;
    this.renames = renames;
  }

  /** A parser of values with these formats, in this order (for tests). */
  static LuxirDateValues forFormats(List<DateTimeFormatter> formats) {
    return new LuxirDateValues(null, List.copyOf(formats), null, List.of(), List.of());
  }

  /** The parser for a request's chain and schema, or null where dates are left to the chain. */
  static LuxirDateValues forRequest(SolrQueryRequest req) {
    var core = req.getCore();
    if (core == null) return null;
    UpdateRequestProcessorChain chain = core.getUpdateProcessorChain(req.getParams());
    LuxirDateValues v = BY_CHAIN.get(chain);
    if (v == null || (v != NONE && v.schema != req.getSchema())) {
      v = build(chain, req.getSchema());
      BY_CHAIN.put(chain, v);
    }
    return v == NONE ? null : v;
  }

  @SuppressWarnings("unchecked")
  private static LuxirDateValues build(UpdateRequestProcessorChain chain, IndexSchema schema) {
    try {
      List<FieldNameSelector> before = new ArrayList<>();
      List<Rename> renames = new ArrayList<>();
      for (UpdateRequestProcessorFactory f : chain.getProcessors()) {
        if (f.getClass() == ParseDateFieldUpdateProcessorFactory.class) {
          Field formats = ParseDateFieldUpdateProcessorFactory.class.getDeclaredField("formats");
          formats.setAccessible(true);
          var list = List.copyOf(((Map<String, DateTimeFormatter>) formats.get(f)).values());
          if (list.isEmpty()) return NONE;
          return new LuxirDateValues(schema, list, selector(f), before, renames);
        } else if (f.getClass() == FieldNameMutatingUpdateProcessorFactory.class) {
          Field pattern = FieldNameMutatingUpdateProcessorFactory.class.getDeclaredField("pattern");
          Field replacement =
              FieldNameMutatingUpdateProcessorFactory.class.getDeclaredField("replacement");
          pattern.setAccessible(true);
          replacement.setAccessible(true);
          renames.add(new Rename((Pattern) pattern.get(f), (String) replacement.get(f)));
        } else if (f instanceof RemoveBlankFieldUpdateProcessorFactory
            || f.getClass() == UUIDUpdateProcessorFactory.class
            || f.getClass() == LogUpdateProcessorFactory.class) {
          // blank removal leaves non-blank strings; the UUID goes to the uniqueKey only
        } else if (f instanceof FieldMutatingUpdateProcessorFactory) {
          before.add(selector(f));
        } else {
          return NONE; // a processor that may change values before parse-date sees them
        }
      }
      return NONE;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return NONE;
    }
  }

  private static FieldNameSelector selector(UpdateRequestProcessorFactory f)
      throws ReflectiveOperationException {
    Method m = FieldMutatingUpdateProcessorFactory.class.getDeclaredMethod("getSelector");
    m.setAccessible(true);
    return (FieldNameSelector) m.invoke(f);
  }

  /** Whether values of this field are parsed here. */
  boolean handles(String field) {
    Boolean b = fields.get(field);
    if (b == null) {
      b = decide(field);
      fields.put(field, b);
    }
    return b;
  }

  private boolean decide(String field) {
    SchemaField sf = schema.getFieldOrNull(field);
    if (sf == null || !(sf.getType() instanceof LuxirDatePointField)) return false;
    if (sf == schema.getUniqueKeyField()) return false;
    for (Rename r : renames) {
      var m = r.pattern().matcher(field);
      if (m.find() && !m.replaceAll(r.replacement()).equals(field)) return false;
    }
    for (FieldNameSelector s : before) {
      if (s.shouldMutate(field)) return false;
    }
    return parseDate.shouldMutate(field);
  }

  /** The value's date as the parse-date processor gives it, or the value itself. */
  Object parse(Object value) {
    if (!(value instanceof CharSequence cs) || cs.length() > MAX_LENGTH) return value;
    String shape = shape(cs);
    Integer format = shapes.get(shape);
    if (format == null) {
      format = firstFormat(cs);
      if (shapes.size() < MAX_SHAPES) shapes.put(shape, format);
    }
    if (format < 0) return value;
    Object date =
        ParseDateFieldUpdateProcessorFactory.parsePossibleDate(
            cs, List.of(formats.get(format)), new ParsePosition(0));
    if (date == null) return value;
    PARSED.increment();
    return date;
  }

  // The processor's own order: the first format that parses this value, or -1
  private int firstFormat(CharSequence cs) {
    ParsePosition pos = new ParsePosition(0);
    for (int i = 0; i < formats.size(); i++) {
      if (ParseDateFieldUpdateProcessorFactory.parsePossibleDate(cs, List.of(formats.get(i)), pos)
          != null) return i;
    }
    return -1;
  }

  // The value with each digit as '9': values of one shape match the same formats
  static String shape(CharSequence cs) {
    char[] c = new char[cs.length()];
    for (int i = 0; i < c.length; i++) {
      char ch = cs.charAt(i);
      c[i] = ch >= '0' && ch <= '9' ? '9' : ch;
    }
    return new String(c);
  }
}
