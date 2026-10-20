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
package org.apache.solr.search.facet;

import org.apache.solr.SolrTestCaseJ4;
import org.junit.BeforeClass;
import org.junit.Test;

/** Stats in a numeric terms facet whose hash table grows while collecting. */
public class TestJsonFacetHashResize extends SolrTestCaseJ4 {
  private static final int VALUES = 1500; // beyond the largest starting table (1024 slots)

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig-tlog.xml", "schema_latest.xml");
    for (int v = 0; v < VALUES; v++) {
      // each value's bucket holds x = v and v + 2: stddev sqrt(2), variance 2, avg v + 1
      assertU(adoc("id", "a" + v, "v_i", "" + v, "x_d", "" + v));
      assertU(adoc("id", "b" + v, "v_i", "" + v, "x_d", "" + (v + 2)));
    }
    assertU(commit());
  }

  @Test
  public void testStatsSurviveRehash() throws Exception {
    // sorted by a stat: it is collected with the buckets, while the table grows
    assertJQ(
        req(
            "q", "*:*",
            "rows", "0",
            "json.facet",
                "{f:{type:terms,field:v_i,method:dvhash,limit:3,sort:'sd desc',"
                    + "facet:{sd:'stddev(x_d)'}}}"),
        "facets=={count:3000, f:{buckets:["
            + "{val:0,count:2,sd:1.4142135623730951},"
            + "{val:1,count:2,sd:1.4142135623730951},"
            + "{val:2,count:2,sd:1.4142135623730951}]}}");
    assertJQ(
        req(
            "q", "*:*",
            "rows", "0",
            "json.facet",
                "{f:{type:terms,field:v_i,method:dvhash,limit:2,sort:'var desc',"
                    + "facet:{var:'variance(x_d)'}}}"),
        "facets=={count:3000, f:{buckets:[{val:0,count:2,var:2.0},{val:1,count:2,var:2.0}]}}");
  }
}
