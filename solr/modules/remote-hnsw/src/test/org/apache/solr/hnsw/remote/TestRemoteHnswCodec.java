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
package org.apache.solr.hnsw.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.SolrTestCase;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class TestRemoteHnswCodec extends SolrTestCase {

  @ClassRule
  public static final EmbeddedSolrServerTestRule solrRule = new EmbeddedSolrServerTestRule();

  private static MockHnswBuildServer mockServer;

  @BeforeClass
  public static void startCluster() throws Exception {
    mockServer = new MockHnswBuildServer();
    System.setProperty("solr.hnsw.build.service.url", mockServer.url());
    solrRule.startSolr();
    Path conf = createTempDir("remote-hnsw-conf");
    Path confSrc = Path.of(TestRemoteHnswCodec.class.getResource("/solr/collection1/conf").toURI());
    PathUtils.copyDirectory(confSrc, conf);
    solrRule.newCollection().withConfigSet(conf).create();
  }

  @AfterClass
  public static void stopMock() {
    if (mockServer != null) {
      mockServer.close();
    }
    System.clearProperty("solr.hnsw.build.service.url");
  }

  @Test
  public void testIndexSearchAndMerge() throws Exception {
    SolrClient client = solrRule.getSolrClient();
    int n = 40;
    List<float[]> vectors = HnswGraphCodecTest.randomVectors(n, 8, 99);
    List<SolrInputDocument> batch1 = new ArrayList<>();
    for (int i = 0; i < n / 2; i++) {
      batch1.add(doc(i, vectors.get(i)));
    }
    client.add(batch1);
    client.commit();

    assertKnnHit(client, vectors.get(3), "3");

    List<SolrInputDocument> batch2 = new ArrayList<>();
    for (int i = n / 2; i < n; i++) {
      batch2.add(doc(i, vectors.get(i)));
    }
    client.add(batch2);
    client.commit();
    client.optimize();

    assertKnnHit(client, vectors.get(n - 2), String.valueOf(n - 2));
  }

  private static void assertKnnHit(SolrClient client, float[] query, String expectedId)
      throws Exception {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("q", "{!knn f=vector topK=3}" + toVectorLiteral(query));
    params.set("fl", "id,score");
    QueryResponse rsp = client.query(params);
    assertTrue(rsp.getResults().getNumFound() > 0);
    List<String> ids = new ArrayList<>();
    rsp.getResults().forEach(doc -> ids.add(String.valueOf(doc.getFieldValue("id"))));
    assertTrue("expected " + expectedId + " in " + ids, ids.contains(expectedId));
  }

  private static SolrInputDocument doc(int id, float[] vector) {
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", String.valueOf(id));
    List<Float> values = new ArrayList<>(vector.length);
    for (float v : vector) {
      values.add(v);
    }
    doc.addField("vector", values);
    return doc;
  }

  private static String toVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(vector[i]);
    }
    sb.append(']');
    return sb.toString();
  }
}
