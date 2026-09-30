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
import org.apache.solr.common.util.EnvUtils;
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Indexes through a live HNSW build service instead of {@link MockHnswBuildServer}. Skipped unless
 * {@code SOLR_HNSW_BUILD_SERVICE_URL} (or {@code -Dsolr.hnsw.build.service.url}) is set.
 *
 * <pre>{@code
 * SOLR_HNSW_BUILD_SERVICE_URL=http://host:18765 ./gradlew \
 *   :solr:modules:remote-hnsw:test --tests ManualRemoteServiceSmokeTest \
 *   -Ptests.useSecurityManager=false
 * }</pre>
 */
public class ManualRemoteServiceSmokeTest extends SolrTestCase {

  @ClassRule
  public static final EmbeddedSolrServerTestRule solrRule = new EmbeddedSolrServerTestRule();

  private static String serviceUrl;

  @BeforeClass
  public static void startCluster() throws Exception {
    serviceUrl = EnvUtils.getProperty(RemoteHnswCodecFactory.SERVICE_URL_PROP);
    Assume.assumeTrue(
        "Set SOLR_HNSW_BUILD_SERVICE_URL to run this against a live HNSW build service",
        serviceUrl != null && !serviceUrl.isBlank());

    solrRule.startSolr();
    Path conf = createTempDir("remote-hnsw-manual-conf");
    Path confSrc =
        Path.of(ManualRemoteServiceSmokeTest.class.getResource("/solr/collection1/conf").toURI());
    PathUtils.copyDirectory(confSrc, conf);
    solrRule.newCollection().withConfigSet(conf).create();
  }

  @AfterClass
  public static void clearServiceUrl() {
    serviceUrl = null;
  }

  @Test
  public void testIndexAndSearchAgainstRealService() throws Exception {
    SolrClient client = solrRule.getSolrClient();
    int n = 200;
    List<float[]> vectors = HnswGraphCodecTest.randomVectors(n, 8, 42);
    List<SolrInputDocument> batch = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      batch.add(doc(i, vectors.get(i)));
    }
    client.add(batch);
    client.commit();

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("q", "{!knn f=vector topK=5}" + toVectorLiteral(vectors.get(7)));
    params.set("fl", "id,score");
    QueryResponse rsp = client.query(params);
    assertTrue("expected results from " + serviceUrl, rsp.getResults().getNumFound() > 0);
    List<String> ids = new ArrayList<>();
    rsp.getResults().forEach(d -> ids.add(String.valueOf(d.getFieldValue("id"))));
    assertTrue("expected exact match id=7 in " + ids, ids.contains("7"));
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
