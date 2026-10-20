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
package org.apache.solr.update;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * The 64-bit hashes of the ids in an {@link IndexWriter}, so that {@link DirectUpdateHandler2} can
 * add a document whose id cannot be in the index yet without a delete term.
 *
 * <p>Lucene buffers the delete term of every {@code updateDocuments} call in each indexing thread's
 * in-memory segment, then sorts and applies them all when the segment is flushed. For ids that were
 * never indexed before, that work is wasted. The set is read once from the writer's index (its id
 * and {@code _root_} terms) and then records every add. A hash collision only makes an add take the
 * normal path.
 *
 * <p>Decisions take a lock only briefly. An add of a new hash is "in flight" until it is in the
 * writer ({@link #landed}). A later add of the same hash waits for it, so its delete is sequenced
 * after that document.
 *
 * <p>One tracker per writer, shared by every handler of the core (a reloaded core keeps its
 * writer). It turns itself off for good when documents reach the writer without passing through the
 * handler ({@code mergeIndexes}, see {@link #disable}), when the writer already had changes on
 * first use, or past {@code maxIds} ids.
 */
final class NewIdTracker {
  /** Returned by {@link #decide} when the add must take the normal path. */
  static final long NO_APPEND = 0;

  private static final Map<IndexWriter, NewIdTracker> TRACKERS = new WeakHashMap<>();

  private static final int STRIPES = 4096; // power of two
  private static final VarHandle LONG =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private static final class Stripe {
    final ReentrantLock lock = new ReentrantLock();
    final Condition landed = lock.newCondition();
    long[] table = new long[16];
    int size;
    long[] flying = new long[4]; // hashes of appends not yet in the writer
    int numFlying;

    boolean isFlying(long hash) {
      for (int i = 0; i < numFlying; i++) if (flying[i] == hash) return true;
      return false;
    }
  }

  private final Stripe[] stripes = new Stripe[STRIPES];
  private final long maxIds;
  private final LongAdder numIds = new LongAdder();
  private volatile boolean disabled;

  NewIdTracker(long maxIds) {
    this.maxIds = maxIds;
    for (int i = 0; i < STRIPES; i++) stripes[i] = new Stripe();
  }

  /**
   * The writer's tracker, read from its index on first use, or null if it is disabled. Handlers get
   * it before every change to the writer, so it is first used before any.
   */
  static NewIdTracker forWriter(IndexWriter writer, String[] idFields, long maxIds)
      throws IOException {
    synchronized (TRACKERS) {
      NewIdTracker t = TRACKERS.get(writer);
      if (t == null) {
        t = new NewIdTracker(maxIds);
        if (writer.hasUncommittedChanges()) {
          // changes made before any handler tracked this writer (e.g. a reload that turned
          // tracking on): ids may be buffered that no reader shows without a flush
          t.disabled = true;
        } else {
          // nothing buffered: this reader flushes nothing and shows all the writer holds
          try (DirectoryReader reader = DirectoryReader.open(writer, false, false)) {
            if (reader.maxDoc() > maxIds) {
              t.disabled = true;
            } else {
              read(t, reader, idFields);
            }
          }
        }
        TRACKERS.put(writer, t);
      }
      return t.disabled ? null : t;
    }
  }

  private static void read(NewIdTracker t, DirectoryReader reader, String[] idFields)
      throws IOException {
    for (LeafReaderContext leaf : reader.leaves()) {
      for (String field : idFields) {
        Terms terms = leaf.reader().terms(field);
        if (terms == null) continue;
        TermsEnum te = terms.iterator();
        for (BytesRef term = te.next(); term != null; term = te.next()) {
          long h = hash(term);
          Stripe s = t.stripe(h);
          if (add(s, h)) t.numIds.increment();
        }
      }
    }
  }

  /** Turns the writer's tracker off, waiting for the appends already decided. */
  static void disable(IndexWriter writer) {
    NewIdTracker t;
    synchronized (TRACKERS) {
      t = TRACKERS.get(writer);
      if (t == null) TRACKERS.put(writer, t = new NewIdTracker(0));
    }
    t.disable();
  }

  /**
   * Records the id; returns its hash if the add may skip its delete ({@code mayAppend}, a new hash,
   * tracker on), in which case the caller must call {@link #landed} with it once the add is done or
   * failed. Otherwise returns {@link #NO_APPEND}, after any append of the same hash has landed.
   */
  long decide(BytesRef id, boolean mayAppend) {
    long hash = hash(id);
    if (hash == NO_APPEND) hash = 1; // 0 marks an empty slot and "no append"
    Stripe s = stripe(hash);
    s.lock.lock();
    try {
      while (s.numFlying > 0 && s.isFlying(hash)) s.landed.awaitUninterruptibly();
      boolean added = add(s, hash);
      if (added) {
        numIds.increment();
        if ((hash & 1023) == 0 && numIds.sum() > maxIds) disabled = true; // checked now and then
      }
      if (!added || !mayAppend || disabled) return NO_APPEND;
      if (s.numFlying == s.flying.length) s.flying = Arrays.copyOf(s.flying, s.numFlying * 2);
      s.flying[s.numFlying++] = hash;
      return hash;
    } finally {
      s.lock.unlock();
    }
  }

  /** The append decided for this hash is in the writer, or failed. */
  void landed(long hash) {
    Stripe s = stripe(hash);
    s.lock.lock();
    try {
      for (int i = 0; i < s.numFlying; i++) {
        if (s.flying[i] == hash) {
          s.flying[i] = s.flying[--s.numFlying];
          break;
        }
      }
      s.landed.signalAll();
    } finally {
      s.lock.unlock();
    }
  }

  long numIds() {
    return numIds.sum();
  }

  boolean isDisabled() {
    return disabled;
  }

  private void disable() {
    disabled = true;
    for (Stripe s : stripes) {
      s.lock.lock();
      try {
        while (s.numFlying > 0) s.landed.awaitUninterruptibly();
      } finally {
        s.lock.unlock();
      }
    }
  }

  private Stripe stripe(long hash) {
    return stripes[(int) (hash >>> 52) & (STRIPES - 1)];
  }

  private static boolean add(Stripe s, long hash) {
    if (hash == 0) hash = 1;
    long[] t = s.table;
    int mask = t.length - 1;
    for (int i = (int) hash & mask; ; i = (i + 1) & mask) {
      long v = t[i];
      if (v == hash) return false;
      if (v == 0) {
        t[i] = hash;
        if (++s.size * 2 > t.length) s.table = grow(t);
        return true;
      }
    }
  }

  private static long[] grow(long[] t) {
    long[] n = new long[t.length * 2];
    int mask = n.length - 1;
    for (long v : t) {
      if (v == 0) continue;
      int i = (int) v & mask;
      while (n[i] != 0) i = (i + 1) & mask;
      n[i] = v;
    }
    return n;
  }

  /** A 64-bit hash of the bytes (murmur3-style mixing, 8 bytes at a time). */
  static long hash(BytesRef b) {
    final long c1 = 0x87c37b91114253d5L, c2 = 0x4cf5ad432745937fL;
    byte[] a = b.bytes;
    int off = b.offset, end = b.offset + b.length;
    long h = 0x9e3779b97f4a7c15L ^ (b.length * c1);
    for (; off + 8 <= end; off += 8) {
      long k = (long) LONG.get(a, off) * c1;
      k = Long.rotateLeft(k, 31) * c2;
      h = Long.rotateLeft(h ^ k, 27) * 5 + 0x52dce729;
    }
    long k = 0;
    for (int shift = 0; off < end; off++, shift += 8) k |= (a[off] & 0xffL) << shift;
    k = Long.rotateLeft(k * c2, 33) * c1;
    h ^= k;
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    return h;
  }
}
