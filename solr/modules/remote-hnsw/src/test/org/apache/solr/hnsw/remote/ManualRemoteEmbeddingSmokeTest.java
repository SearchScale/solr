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
import org.apache.solr.client.solrj.request.UpdateRequest;
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
 * Live service: {@code /v1/embed} then {@code /v1/hnsw/build}, using {@code hash-demo}. Skipped
 * unless {@code SOLR_HNSW_BUILD_SERVICE_URL} is set.
 *
 * <pre>{@code
 * SOLR_HNSW_BUILD_SERVICE_URL=http://host:18765 ./gradlew \
 *   :solr:modules:remote-hnsw:test --tests ManualRemoteEmbeddingSmokeTest \
 *   -Ptests.useSecurityManager=false
 * }</pre>
 */
public class ManualRemoteEmbeddingSmokeTest extends SolrTestCase {

  @ClassRule
  public static final EmbeddedSolrServerTestRule solrRule = new EmbeddedSolrServerTestRule();

  private static String serviceUrl;

  @BeforeClass
  public static void startCluster() throws Exception {
    serviceUrl = EnvUtils.getProperty(RemoteHnswCodecFactory.SERVICE_URL_PROP);
    Assume.assumeTrue(
        "Set SOLR_HNSW_BUILD_SERVICE_URL to run this against a live HnswBuildServer",
        serviceUrl != null && !serviceUrl.isBlank());
    System.setProperty("solr.embed.service.url", serviceUrl);

    solrRule.startSolr();
    Path conf = createTempDir("remote-embed-manual-conf");
    Path confSrc =
        Path.of(
            ManualRemoteEmbeddingSmokeTest.class
                .getResource("/solr/manual-embed-collection/conf")
                .toURI());
    PathUtils.copyDirectory(confSrc, conf);
    solrRule.newCollection().withConfigSet(conf).create();
  }

  @AfterClass
  public static void clearServiceUrl() {
    serviceUrl = null;
    System.clearProperty("solr.embed.service.url");
  }

  @Test
  public void testIndexByTextAndSearchAgainstRealService() throws Exception {
    SolrClient client = solrRule.getSolrClient();

    String[] contents = {
      "the quick brown fox jumps over the lazy dog",
      "a fast brown fox leaps over a sleepy dog",
      "completely unrelated content about tax law",
      "another unrelated document about tax filings",
      "the weather today is sunny with light wind",
      "sunny skies and a light breeze this afternoon",
      "quantum computing and superconducting qubits",
      "superconductors used to build quantum computers",
      "a ninth document to round out the batch",
      "a tenth and final document about gardening tips"
    };

    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < contents.length; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", String.valueOf(i));
      doc.addField("content", contents[i]);
      docs.add(doc);
    }

    UpdateRequest addRequest = new UpdateRequest();
    addRequest.add(docs);
    addRequest.setParam("update.chain", "remote-embed");
    addRequest.process(client);
    client.commit();

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("q", "{!remote_embed f=vector topK=3}a quick brown fox jumps over a lazy dog");
    params.set("fl", "id,score");
    QueryResponse rsp = client.query(params);
    assertTrue("expected results from " + serviceUrl, rsp.getResults().getNumFound() > 0);
    String topId = String.valueOf(rsp.getResults().get(0).getFieldValue("id"));
    assertTrue(
        "expected doc 0 ('...quick brown fox...') to be the closest match against "
            + serviceUrl
            + ", got id="
            + topId,
        topId.equals("0") || topId.equals("1"));
  }
}
