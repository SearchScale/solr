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

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.ByteArrayUtf8CharSequence;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.handler.UpdateRequestHandler;
import org.apache.solr.handler.loader.ContentStreamLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;

/**
 * A JSON update loader that parses the request bytes directly. For the common body, an array of
 * flat documents, string values become zero-copy UTF-8 slices of the request ({@link
 * ByteArrayUtf8CharSequence}), so text bound for a {@link LuxirTextField} reaches Luxir without
 * ever becoming a Java String. Where the update chain allows it, documents go straight to Lucene
 * documents ({@link DirectDocBuilder}) without a {@code SolrInputDocument}. Bodies of documents
 * only ({@code /update/json/docs}, or {@code json.command=false}) are read the same way when they
 * are flat documents, one after another or in an array, split at the root with each field under its
 * own name (Solr's default {@code split=/} and {@code f=$FQN:/**}): those parameters then map each
 * document to itself. Anything else (command objects, atomic updates, child documents, other
 * split/mapping parameters) goes to Solr's standard JSON loader unchanged. Date strings of Luxir
 * date fields arrive parsed, as the chain's parse-date processor would parse them ({@link
 * LuxirDateValues}).
 */
public class FastJsonDocsLoader extends ContentStreamLoader {
  // JsonLoader parameters that change how a body is read: leave those requests to it
  private static final Set<String> SPECIAL_PARAMS =
      Set.of("split", "f", "srcField", "mapUniqueKeyOnly", "echo", "json.command", "df");

  private final ContentStreamLoader fallback;
  private final boolean direct;

  /** Requests parsed by the fast path (for tests and monitoring). */
  static final java.util.concurrent.atomic.LongAdder FAST_REQUESTS =
      new java.util.concurrent.atomic.LongAdder();

  /** Documents indexed through the direct path (for tests and monitoring). */
  static final java.util.concurrent.atomic.LongAdder DIRECT_DOCS =
      new java.util.concurrent.atomic.LongAdder();

  /**
   * @param direct whether documents may skip {@code SolrInputDocument} where the chain allows
   */
  public FastJsonDocsLoader(ContentStreamLoader fallback, boolean direct) {
    this.fallback = fallback;
    this.direct = direct;
  }

  @Override
  public void load(
      SolrQueryRequest req,
      SolrQueryResponse rsp,
      ContentStream stream,
      UpdateRequestProcessor processor)
      throws Exception {
    byte[] body;
    try (InputStream in = stream.getStream()) {
      body = in.readAllBytes();
    }
    SolrParams params = req.getParams();
    List<Parsed> docs = null;
    if (docsOnly(req)) {
      if (identitySplit(params)) docs = new Parser(body).docStream();
    } else if (!hasSpecialParams(params)) {
      docs = new Parser(body).docs();
    }
    if (docs == null) {
      ContentStreamBase.ByteArrayStream copy =
          new ContentStreamBase.ByteArrayStream(body, stream.getName());
      copy.setContentType(stream.getContentType());
      fallback.load(req, rsp, copy, processor);
      return;
    }
    FAST_REQUESTS.increment();
    boolean overwrite = params.getBool(UpdateParams.OVERWRITE, true);
    int commitWithin = params.getInt(UpdateParams.COMMIT_WITHIN, -1);
    DirectDocBuilder builder = direct ? DirectDocBuilder.forRequest(req) : null;
    LuxirDateValues dates = LuxirDateValues.forRequest(req);
    for (Parsed doc : docs) {
      if (dates != null) {
        for (int i = 0; i < doc.size; i++) {
          if (dates.handles(doc.keys[i])) doc.values[i] = dates.parse(doc.values[i]);
        }
      }
      AddUpdateCommand cmd = builder == null ? null : builder.command(req, doc);
      if (cmd == null) {
        cmd = new AddUpdateCommand(req);
        cmd.solrDoc = doc.toSolrInputDocument();
      } else {
        DIRECT_DOCS.increment();
      }
      cmd.overwrite = overwrite;
      cmd.commitWithin = commitWithin;
      processor.processAdd(cmd);
    }
  }

  // As Solr's JSON loader: the body holds documents only, read by split and field mappings
  private static boolean docsOnly(SolrQueryRequest req) {
    return UpdateRequestHandler.DOC_PATH.equals(req.getContext().get(CommonParams.PATH))
        || "false".equals(req.getParams().get("json.command"));
  }

  // Solr's default split and mapping, which index a flat document as it is, and nothing else
  private static boolean identitySplit(SolrParams params) {
    String split = params.get("split");
    String[] f = params.getParams("f");
    if (split != null && !split.equals("/")) return false;
    if (f != null && !(f.length == 1 && f[0].equals("$FQN:/**"))) return false;
    var names = params.getParameterNamesIterator();
    while (names.hasNext()) {
      String n = names.next();
      if (n.equals("srcField")
          || n.equals("mapUniqueKeyOnly")
          || n.equals("echo")
          || n.startsWith("split.")) return false;
    }
    return true;
  }

  private static boolean hasSpecialParams(SolrParams params) {
    var names = params.getParameterNamesIterator();
    while (names.hasNext()) {
      String n = names.next();
      if (SPECIAL_PARAMS.contains(n) || n.startsWith("split.")) return true;
    }
    return false;
  }

  /**
   * A parsed flat document: its values in order, each with its field name (a multi-valued field's
   * array gives consecutive entries). Values are never null.
   */
  static final class Parsed {
    String[] keys = new String[16];
    Object[] values = new Object[16];
    int size;

    void add(String key, Object value) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, size * 2);
        values = Arrays.copyOf(values, size * 2);
      }
      keys[size] = key;
      values[size++] = value;
    }

    SolrInputDocument toSolrInputDocument() {
      SolrInputDocument doc = new SolrInputDocument();
      for (int i = 0; i < size; i++) doc.addField(keys[i], values[i]);
      return doc;
    }
  }

  /** A JSON array of flat documents; {@link #docs()} returns null for anything else. */
  static final class Parser {
    private static final class Unsupported extends RuntimeException {
      Unsupported() {
        super(null, null, false, false);
      }
    }

    private static final Unsupported UNSUPPORTED = new Unsupported();
    private static final VarHandle LONG =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;
    private static final long QUOTES = ONES * '"';
    private static final long BACKSLASHES = ONES * '\\';

    private final byte[] b;
    private int p;
    // keys by position in the previous document, reused while the bytes match
    private byte[][] keyBytes = new byte[16][];
    private String[] keyStrings = new String[16];

    Parser(byte[] b) {
      this.b = b;
    }

    /**
     * Flat documents one after another, each alone or in an array, as Solr reads a body of
     * documents split at the root; null for anything else.
     */
    List<Parsed> docStream() {
      try {
        List<Parsed> out = new ArrayList<>();
        for (ws(); p < b.length; ws()) {
          if (peek() != '[') {
            out.add(doc());
            continue;
          }
          p++;
          ws();
          if (peek() == ']') {
            p++;
            continue;
          }
          for (; ; ) {
            ws();
            out.add(doc());
            ws();
            byte c = next();
            if (c == ']') break;
            if (c != ',') throw UNSUPPORTED;
          }
        }
        return out;
      } catch (Unsupported | ArrayIndexOutOfBoundsException | NumberFormatException e) {
        return null;
      }
    }

    List<Parsed> docs() {
      try {
        ws();
        expect('[');
        List<Parsed> out = new ArrayList<>();
        ws();
        if (peek() == ']') {
          p++;
        } else {
          for (; ; ) {
            ws();
            out.add(doc());
            ws();
            byte c = next();
            if (c == ']') break;
            if (c != ',') throw UNSUPPORTED;
          }
        }
        ws();
        if (p != b.length) throw UNSUPPORTED;
        return out;
      } catch (Unsupported | ArrayIndexOutOfBoundsException | NumberFormatException e) {
        return null;
      }
    }

    private Parsed doc() {
      expect('{');
      Parsed doc = new Parsed();
      ws();
      if (peek() == '}') {
        p++;
        return doc;
      }
      for (int k = 0; ; k++) {
        ws();
        String key = key(k);
        ws();
        expect(':');
        ws();
        byte c = peek();
        if (c == '[') {
          p++;
          ws();
          if (peek() == ']') {
            p++;
          } else {
            for (; ; ) {
              ws();
              Object v = scalar();
              if (v != null) doc.add(key, v);
              ws();
              byte d = next();
              if (d == ']') break;
              if (d != ',') throw UNSUPPORTED;
            }
          }
        } else {
          Object v = scalar();
          if (v != null) doc.add(key, v);
        }
        ws();
        byte d = next();
        if (d == '}') return doc;
        if (d != ',') throw UNSUPPORTED;
      }
    }

    /** The k-th key of a document: the previous document's String when the bytes are the same. */
    private String key(int k) {
      expect('"');
      int start = p;
      scan();
      if (b[p] == '\\') {
        p = start - 1;
        return string().toString();
      }
      int end = p++;
      if (k >= keyBytes.length) {
        keyBytes = Arrays.copyOf(keyBytes, k * 2);
        keyStrings = Arrays.copyOf(keyStrings, k * 2);
      }
      byte[] cached = keyBytes[k];
      if (cached != null && Arrays.equals(b, start, end, cached, 0, cached.length)) {
        return keyStrings[k];
      }
      String s = new String(b, start, end - start, StandardCharsets.UTF_8);
      keyBytes[k] = Arrays.copyOfRange(b, start, end);
      keyStrings[k] = s;
      return s;
    }

    // a string, number, boolean or null; objects and nested arrays are not handled here
    private Object scalar() {
      byte c = peek();
      if (c == '"') return string();
      if (c == '-' || (c >= '0' && c <= '9')) return number();
      if (bareStart(c)) return bare();
      throw UNSUPPORTED;
    }

    /**
     * An unquoted word, which Solr's parser reads as true, false, null or else a string (such as
     * {@code Infinity} in some data sets). Only ASCII words are read here.
     */
    private Object bare() {
      int start = p++;
      while (p < b.length && bareChar(b[p])) p++;
      if (p < b.length && (b[p] == '\\' || b[p] < 0)) throw UNSUPPORTED; // escapes, non-ASCII
      int len = p - start;
      if (len == 4
          && b[start] == 't'
          && b[start + 1] == 'r'
          && b[start + 2] == 'u'
          && b[start + 3] == 'e') return Boolean.TRUE;
      if (len == 4
          && b[start] == 'n'
          && b[start + 1] == 'u'
          && b[start + 2] == 'l'
          && b[start + 3] == 'l') return null;
      if (len == 5
          && b[start] == 'f'
          && b[start + 1] == 'a'
          && b[start + 2] == 'l'
          && b[start + 3] == 's'
          && b[start + 4] == 'e') return Boolean.FALSE;
      return new ByteArrayUtf8CharSequence(b, start, len);
    }

    // As Solr's parser (Java identifier characters), for ASCII
    private static boolean bareStart(byte c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean bareChar(byte c) {
      return bareStart(c) || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '/';
    }

    private Object number() {
      int start = p;
      boolean real = false;
      while (p < b.length) {
        byte c = b[p];
        if (c >= '0' && c <= '9' || c == '-' || c == '+') {
          p++;
        } else if (c == '.' || c == 'e' || c == 'E') {
          real = true;
          p++;
        } else {
          break;
        }
      }
      if (!real) {
        // an integer of up to 18 digits: straight from the bytes
        int i = start;
        boolean neg = b[i] == '-';
        if (neg) i++;
        if (p - i > 0 && p - i <= 18) {
          long v = 0;
          for (; i < p; i++) {
            int d = b[i] - '0';
            if (d < 0 || d > 9) throw new NumberFormatException();
            v = v * 10 + d;
          }
          return neg ? -v : v;
        }
      }
      String s = new String(b, start, p - start, StandardCharsets.US_ASCII);
      return real ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
    }

    /** Advances p to the next quote or backslash, eight bytes at a time. */
    private void scan() {
      int n = b.length;
      while (p + Long.BYTES <= n) {
        long w = (long) LONG.get(b, p);
        long q = w ^ QUOTES, s = w ^ BACKSLASHES;
        long hit = ((q - ONES) & ~q | (s - ONES) & ~s) & HIGHS;
        if (hit != 0) {
          p += Long.numberOfTrailingZeros(hit) >>> 3;
          return;
        }
        p += Long.BYTES;
      }
      while (b[p] != '"' && b[p] != '\\') p++;
    }

    /** A JSON string as UTF-8: a slice of the body when it has no escapes, else decoded. */
    private ByteArrayUtf8CharSequence string() {
      expect('"');
      int start = p;
      scan();
      if (b[p] == '"') {
        p++;
        return new ByteArrayUtf8CharSequence(b, start, p - 1 - start);
      }
      return escaped(start);
    }

    private ByteArrayUtf8CharSequence escaped(int start) {
      byte[] out = new byte[64 + (p - start) * 2];
      int n = p - start;
      System.arraycopy(b, start, out, 0, n);
      while (true) {
        byte c = b[p++];
        if (c == '"') break;
        // c is a backslash: decode the escape, then copy the run up to the next quote or escape
        if (n + 8 > out.length) out = Arrays.copyOf(out, out.length * 2);
        byte e = b[p++];
        switch (e) {
          case '"', '\\', '/' -> out[n++] = e;
          case 'b' -> out[n++] = '\b';
          case 'f' -> out[n++] = '\f';
          case 'n' -> out[n++] = '\n';
          case 'r' -> out[n++] = '\r';
          case 't' -> out[n++] = '\t';
          case 'u' -> {
            int cp = hex4();
            if (Character.isHighSurrogate((char) cp) && b[p] == '\\' && b[p + 1] == 'u') {
              p += 2;
              int low = hex4();
              cp = Character.toCodePoint((char) cp, (char) low);
            }
            n = utf8(cp, out, n);
          }
          default -> throw UNSUPPORTED;
        }
        int run = p;
        scan();
        int len = p - run;
        if (n + len + 8 > out.length)
          out = Arrays.copyOf(out, Math.max(out.length * 2, n + len + 64));
        System.arraycopy(b, run, out, n, len);
        n += len;
      }
      return new ByteArrayUtf8CharSequence(out, 0, n);
    }

    private int hex4() {
      int v = Integer.parseInt(new String(b, p, 4, StandardCharsets.US_ASCII), 16);
      p += 4;
      return v;
    }

    private static int utf8(int cp, byte[] out, int n) {
      if (cp < 0x80) {
        out[n++] = (byte) cp;
      } else if (cp < 0x800) {
        out[n++] = (byte) (0xC0 | (cp >> 6));
        out[n++] = (byte) (0x80 | (cp & 0x3F));
      } else if (cp < 0x10000) {
        out[n++] = (byte) (0xE0 | (cp >> 12));
        out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        out[n++] = (byte) (0x80 | (cp & 0x3F));
      } else {
        out[n++] = (byte) (0xF0 | (cp >> 18));
        out[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        out[n++] = (byte) (0x80 | (cp & 0x3F));
      }
      return n;
    }

    private void ws() {
      while (p < b.length) {
        byte c = b[p];
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
        p++;
      }
    }

    private byte peek() {
      return b[p];
    }

    private byte next() {
      return b[p++];
    }

    private void expect(char c) {
      if (b[p++] != c) throw UNSUPPORTED;
    }
  }
}
