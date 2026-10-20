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

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@link LuxirValueFieldsTest} without an update log: JSON documents are built straight into Lucene
 * documents ({@link DirectDocBuilder}), XML ones through Solr's DocumentBuilder, and every
 * comparison with the stock reference fields (which the direct path fills through copyFields) must
 * still hold.
 */
public class LuxirValueFieldsDirectTest extends LuxirValueFieldsTest {
  static long directBefore;

  @BeforeClass
  public static void beforeDirect() throws Exception {
    deleteCore(); // the one LuxirValueFieldsTest set up, with an update log
    directBefore = FastJsonDocsLoader.DIRECT_DOCS.sum();
    init(false);
  }

  @Test
  public void testDirectPathUsed() {
    assertTrue(FastJsonDocsLoader.DIRECT_DOCS.sum() - directBefore > NUM_DOCS / 4);
  }

  @Test
  public void testStandardErrorsKept() throws Exception {
    // documents the direct path leaves to Solr's DocumentBuilder fail as before
    for (String json :
        new String[] {
          "[{\"id\":\"e1\",\"nosuchfield\":\"x\"}]",
          "[{\"id\":\"e2\",\"kind_s\":[\"a\",\"b\"]}]",
          "[{\"kind_s\":\"a\"}]",
          "[{\"id\":\"e4\",\"year_i\":\"notanumber\"}]"
        }) {
      expectThrows(Exception.class, () -> post("/update", json));
    }
    assertU(commit());
    assertQ(req("q", "id:e*"), "//result[@numFound='0']");
  }
}
