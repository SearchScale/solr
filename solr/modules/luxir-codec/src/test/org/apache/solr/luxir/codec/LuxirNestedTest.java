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

import static org.apache.solr.luxir.codec.LuxirValueFieldsTest.same;

import java.util.Random;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Blocks of nested documents whose children carry Luxir fields: block joins, block-join facet
 * domains and uniqueBlock() answer as over the reference (stock) fields.
 */
public class LuxirNestedTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", getFile("luxir-nested/solr")); // luxir-values with _root_
    Random r = new Random(5);
    StringBuilder json = new StringBuilder("[");
    for (int p = 0; p < 60; p++) {
      if (p > 0) json.append(',');
      json.append("{\"id\":\"p").append(p).append("\",\"kind_s\":\"parent\",\"year_i\":").append(1990 + p % 30);
      json.append(",\"_childDocuments_\":[");
      int kids = r.nextInt(6);
      for (int c = 0; c < kids; c++) {
        if (c > 0) json.append(',');
        json.append("{\"id\":\"p").append(p).append('c').append(c).append("\",\"kind_s\":\"k").append(r.nextInt(4));
        json.append("\",\"year_i\":").append(2000 + r.nextInt(20));
        json.append(",\"cats_ss\":[\"c").append(r.nextInt(5)).append("\",\"c").append(r.nextInt(5)).append("\"]}");
      }
      json.append("]}");
      if (p % 17 == 16) {
        LuxirValueFieldsTest.post("/update", json.append(']').toString());
        assertU(commit());
        json.setLength(0);
        json.append('[');
        p++; // keep ids distinct: the next one starts a new batch
        json.append("{\"id\":\"p").append(p).append("\",\"kind_s\":\"parent\",\"_childDocuments_\":[]}");
      }
    }
    LuxirValueFieldsTest.post("/update", json.append(']').toString());
    assertU(commit());
  }

  @Test
  public void testBlockJoins() throws Exception {
    same("q", "{!parent which=kind_s:parent}year_i:[2005 TO 2010]", "fl", "id", "sort", "id asc", "rows", "100");
    same("q", "{!parent which=kind_s:parent score=max}cats_ss:c1", "fl", "id", "sort", "id asc", "rows", "100");
    same("q", "{!child of=kind_s:parent}+kind_s:parent +year_i:[1995 TO 2000]", "fl", "id", "sort", "id asc", "rows", "200");
  }

  @Test
  public void testBlockJoinFacets() throws Exception {
    // uniqueBlock() by a query counts blocks and has no field: not accelerated as unique(). (By
    // _root_, it trips an assertion in Solr's own UniqueSinglevaluedSlotAcc, over stock fields too.)
    for (String unique : new String[] {"uniqueBlock({!v=kind_s:parent})"}) {
      same(
          "q", "kind_s:parent", "rows", "0",
          "json.facet",
          "{k:{type:terms,field:kind_s,sort:'parents desc',domain:{blockChildren:'kind_s:parent'},"
              + "facet:{parents:'" + unique + "'}}}");
      same(
          "q", "kind_s:parent", "rows", "0",
          "json.facet",
          "{c:{type:terms,field:cats_ss,sort:'parents desc',domain:{blockChildren:'kind_s:parent'},"
              + "facet:{parents:'" + unique + "'}}}");
    }
    same(
        "q", "kind_s:parent", "rows", "0",
        "json.facet", "{k:{type:terms,field:kind_s,domain:{blockChildren:'kind_s:parent'},facet:{y:'max(year_i)'}}}");
    same(
        "q", "cats_ss:c2", "rows", "0",
        "json.facet", "{y:{type:terms,field:year_i,limit:50,domain:{blockParent:'kind_s:parent'}}}");
  }
}
