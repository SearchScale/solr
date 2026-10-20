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

import org.apache.solr.SolrTestCaseJ4;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A numeric range alone, by score, on segments without deletes: Luxir counts it without executing
 * it (from the column's metadata, or from its points), and must count as Solr's own.
 */
public class LuxirRangeCountTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    LuxirValueFieldsTest.init(true, false);
  }

  @AfterClass
  public static void afterClass() {
    LuxirValueFieldsTest.afterClass();
  }

  @Test
  public void testRangeCounts() throws Exception {
    for (String q :
        new String[] {
          "year_i:[2000 TO 2010]",
          "year_i:[* TO *]",
          "year_i:[5000 TO *]",
          "rev_l:{0 TO *]",
          "ts_dt:[2010-01-01T00:00:00Z TO *]",
          "n_is:7"
        }) {
      same("q", q, "rows", "0");
      same("q", q, "fl", "id", "rows", "20");
    }
  }
}
