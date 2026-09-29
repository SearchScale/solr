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
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/** Embed + graph-build through mock HTTP servers, then knn search. Documents start as text only. */
public class RemoteEmbeddingEndToEndTest extends SolrTestCase {

  @ClassRule
  public static final EmbeddedSolrServerTestRule solrRule = new EmbeddedSolrServerTestRule();

  private static MockEmbedServer embedServer;
  private static MockHnswBuildServer buildServer;

  @BeforeClass
  public static void startServicesAndCollection() throws Exception {
    embedServer = new MockEmbedServer(32);
    buildServer = new MockHnswBuildServer();
    System.setProperty("solr.embed.service.url", embedServer.url());
    // Small on purpose: with 9 docs and batchSize=4 we expect 3 embed calls (4+4+1), proving
    // documents are actually batched rather than sent one-by-one.
    System.setProperty("solr.embed.batchSize", "4");
    System.setProperty("solr.hnsw.build.service.url", buildServer.url());

    solrRule.startSolr();
    Path conf = createTempDir("remote-embed-e2e-conf");
    Path confSrc =
        Path.of(
            RemoteEmbeddingEndToEndTest.class.getResource("/solr/embed-collection/conf").toURI());
    PathUtils.copyDirectory(confSrc, conf);
    solrRule.newCollection().withConfigSet(conf).create();
  }

  @AfterClass
  public static void stopServicesAndClearProperties() {
    System.clearProperty("solr.embed.service.url");
    System.clearProperty("solr.embed.batchSize");
    System.clearProperty("solr.hnsw.build.service.url");
    if (embedServer != null) {
      embedServer.close();
    }
    if (buildServer != null) {
      buildServer.close();
    }
  }

  @Test
  public void testIndexByTextAndSearchByVector() throws Exception {
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
      "a ninth document to round out the batch"
    };

    List<SolrInputDocument> docs = new ArrayList<>();
    for (int i = 0; i < contents.length; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", String.valueOf(i));
      doc.addField("content", contents[i]);
      // deliberately no "vector" field - it must be produced remotely
      docs.add(doc);
    }

    UpdateRequest addRequest = new UpdateRequest();
    addRequest.add(docs);
    addRequest.setParam("update.chain", "remote-embed");
    addRequest.process(client);
    client.commit();

    // 9 docs, batchSize=4 -> 3 embed calls, not 9: proves batching, not per-doc calls.
    assertEquals(
        "expected embedding requests to be batched, not one per document",
        3,
        embedServer.requestCount());

    // Query with a vector generated the same way the "fox" document's content was, embedded
    // locally with the same deterministic model - it should come back as the top hit.
    HashTextEmbedder localEmbedder = new HashTextEmbedder(32);
    float[] queryVector =
        localEmbedder.embedBatch(List.of("a quick brown fox jumps over a lazy dog"))[0];

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("q", "{!knn f=vector topK=3}" + toVectorLiteral(queryVector));
    params.set("fl", "id,score");
    QueryResponse rsp = client.query(params);
    assertTrue("expected at least one hit", rsp.getResults().getNumFound() > 0);
    String topId = String.valueOf(rsp.getResults().get(0).getFieldValue("id"));
    assertTrue(
        "expected doc 0 ('...quick brown fox...') to be the closest match, got id=" + topId,
        topId.equals("0") || topId.equals("1"));

    int embedCallsAfterIndex = embedServer.requestCount();
    ModifiableSolrParams textQuery = new ModifiableSolrParams();
    textQuery.set("q", "{!remote_embed f=vector topK=3}a quick brown fox jumps over a lazy dog");
    textQuery.set("fl", "id,score");
    QueryResponse textRsp = client.query(textQuery);
    assertTrue("expected hits from remote_embed", textRsp.getResults().getNumFound() > 0);
    String textTopId = String.valueOf(textRsp.getResults().get(0).getFieldValue("id"));
    assertTrue(
        "expected fox docs from {!remote_embed}, got id=" + textTopId,
        textTopId.equals("0") || textTopId.equals("1"));
    assertEquals(
        "search-by-text should make one extra /v1/embed call",
        embedCallsAfterIndex + 1,
        embedServer.requestCount());
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
