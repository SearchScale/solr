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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.util.RefCounted;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Without an update log, {@link DirectUpdateHandler2} adds documents with new ids without a delete
 * ({@link NewIdTracker}); a repeated id must still replace its previous version, whether it was
 * added in this writer's life, read from the index after a new writer or a core reload, or added
 * concurrently.
 */
public class DirectUpdateHandlerNewIdsTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    System.setProperty("solr.index.updatelog.enabled", "false");
    initCore("solrconfig.xml", "schema.xml");
  }

  @Before
  public void clear() {
    assertU(delQ("*:*"));
    assertU(commit());
  }

  @Test
  public void testTracking() throws Exception {
    for (int i = 0; i < 10; i++) assertU(adoc("id", id(1, i), "v_l", "1"));
    assertU(adoc("id", id(1, 3), "v_l", "2"));
    NewIdTracker ids = tracker();
    assertNotNull("tracking without an update log", ids);
    assertTrue(ids.numIds() >= 10);
    assertU(commit());
    assertQ(req("q", "id:1*", "rows", "0"), "//result[@numFound='10']");
  }

  @Test
  public void testRepeatedIdsReplace() throws Exception {
    Random r = random();
    Map<String, Integer> last = new HashMap<>();
    for (int i = 0; i < 2000; i++) {
      String id = id(2, r.nextInt(700));
      int v = r.nextInt(1_000_000);
      if (r.nextInt(20) == 0) {
        // overwrite=false: a duplicate on purpose; its id is tracked too
        String dup = id(3, i);
        assertU(add(doc("id", dup, "v_l", "0"), "overwrite", "false"));
        assertU(adoc("id", dup, "v_l", Integer.toString(v)));
        last.put(dup, v);
        continue;
      }
      assertU(adoc("id", id, "v_l", Integer.toString(v)));
      last.put(id, v);
      if (r.nextInt(150) == 0) assertU(commit());
      if (r.nextInt(400) == 0) { // ids deleted stay tracked: re-adding them overwrites
        String gone = id(2, r.nextInt(700));
        assertU(delI(gone));
        last.remove(gone);
      }
    }
    assertU(commit());
    check(last);
  }

  @Test
  public void testIdsReadFromIndex() throws Exception {
    Map<String, Integer> last = new HashMap<>();
    for (int i = 0; i < 300; i++) {
      assertU(adoc("id", id(4, i), "v_l", Integer.toString(i)));
      last.put(id(4, i), i);
    }
    assertU(commit());
    // a new writer: its tracker is read from the index
    h.getCore().getUpdateHandler().newIndexWriter(false);
    for (int i = 0; i < 300; i += 3) {
      assertU(adoc("id", id(4, i), "v_l", Integer.toString(-i)));
      last.put(id(4, i), -i);
    }
    assertU(commit());
    check(last);
    // a reloaded core: a new handler, the same writer and tracker
    h.reload();
    for (int i = 1; i < 300; i += 3) {
      assertU(adoc("id", id(4, i), "v_l", Integer.toString(-i)));
      last.put(id(4, i), -i);
    }
    assertU(commit());
    h.getCore().getUpdateHandler().newIndexWriter(false);
    for (int i = 2; i < 300; i += 3) {
      assertU(adoc("id", id(4, i), "v_l", Integer.toString(-i)));
      last.put(id(4, i), -i);
    }
    assertU(commit());
    check(last);
  }

  @Test
  public void testNestedDocumentsReplaced() throws Exception {
    SolrInputDocument parent = sdoc("id", id(6, 1), "v_l", "1");
    parent.addChildDocument(sdoc("id", id(6, 11), "v_l", "1"));
    parent.addChildDocument(sdoc("id", id(6, 12), "v_l", "1"));
    assertU(adoc(parent));
    SolrInputDocument again = sdoc("id", id(6, 1), "v_l", "2");
    again.addChildDocument(sdoc("id", id(6, 13), "v_l", "2"));
    assertU(adoc(again));
    assertU(commit());
    assertQ(req("q", "*:*", "rows", "0"), "//result[@numFound='2']");
    assertQ(req("q", "id:" + id(6, 13), "rows", "0"), "//result[@numFound='1']");
  }

  @Test
  public void testConcurrentAdds() throws Exception {
    int threads = 8, perThread = 400, numIds = 300;
    Set<String> used = ConcurrentHashMap.newKeySet();
    ExecutorService pool =
        ExecutorUtil.newMDCAwareFixedThreadPool(threads, new SolrNamedThreadFactory("testAdds"));
    try {
      List<Future<?>> fs = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        long seed = random().nextLong();
        fs.add(
            pool.submit(
                () -> {
                  Random r = new Random(seed);
                  for (int i = 0; i < perThread; i++) {
                    String id = id(5, r.nextInt(numIds));
                    used.add(id);
                    assertU(adoc("id", id, "v_l", Integer.toString(i)));
                  }
                  return null;
                }));
      }
      for (Future<?> f : fs) f.get();
    } finally {
      ExecutorUtil.shutdownAndAwaitTermination(pool);
    }
    assertU(commit());
    assertQ(req("q", "id:5*", "rows", "0"), "//result[@numFound='" + used.size() + "']");
  }

  @Test
  public void testSameHashWaitsForAppendInFlight() throws Exception {
    NewIdTracker ids = new NewIdTracker(Long.MAX_VALUE);
    BytesRef id = new BytesRef("same");
    long h = ids.decide(id, true);
    assertNotEquals("a new id appends", NewIdTracker.NO_APPEND, h);
    ExecutorService pool =
        ExecutorUtil.newMDCAwareSingleThreadExecutor(new SolrNamedThreadFactory("testIds"));
    try {
      Future<Long> second = pool.submit(() -> ids.decide(id, true));
      Thread.sleep(200);
      assertFalse("waits while the first append is on its way to the writer", second.isDone());
      long other = ids.decide(new BytesRef("other"), true);
      assertNotEquals("another id does not wait", NewIdTracker.NO_APPEND, other);
      ids.landed(h);
      assertEquals(
          "then overwrites", NewIdTracker.NO_APPEND, (long) second.get(10, TimeUnit.SECONDS));
      ids.landed(other);
    } finally {
      ExecutorUtil.shutdownAndAwaitTermination(pool);
    }
  }

  @Test
  public void testMaxIds() {
    NewIdTracker ids = new NewIdTracker(100);
    int appends = 0;
    for (int i = 0; i < 200_000; i++) {
      long h = ids.decide(new BytesRef("m" + i), true);
      if (h != NewIdTracker.NO_APPEND) {
        appends++;
        ids.landed(h);
      }
    }
    assertTrue("turns itself off past maxIds", ids.isDisabled());
    assertTrue(appends < 200_000);
  }

  /** A 5-digit id (the test schema copies ids to a numeric field): group digit, then n. */
  private static String id(int group, int n) {
    return Integer.toString(group * 10000 + n);
  }

  private static NewIdTracker tracker() throws Exception {
    RefCounted<IndexWriter> iw = h.getCore().getSolrCoreState().getIndexWriter(h.getCore());
    try {
      return NewIdTracker.forWriter(iw.get(), new String[] {"id"}, Long.MAX_VALUE);
    } finally {
      iw.decref();
    }
  }

  private static void check(Map<String, Integer> last) {
    assertQ(req("q", "*:*", "rows", "0"), "//result[@numFound='" + last.size() + "']");
    int k = 0;
    for (Map.Entry<String, Integer> e : last.entrySet()) {
      if (k++ % 7 != 0) continue;
      assertQ(
          req("q", "id:" + e.getKey(), "fl", "v_l"),
          "//result[@numFound='1']",
          "//arr[@name='v_l']/long[.='" + e.getValue() + "']");
    }
  }
}
