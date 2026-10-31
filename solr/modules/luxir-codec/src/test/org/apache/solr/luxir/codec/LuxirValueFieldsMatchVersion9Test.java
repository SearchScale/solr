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

import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * {@link LuxirValueFieldsTest} with luceneMatchVersion 9.0, as many existing configsets have: the
 * query parser then leaves purely negative queries as they are, for the searcher to fix, so
 * Luxir's paths must fix them as SolrIndexSearcher does.
 */
public class LuxirValueFieldsMatchVersion9Test extends LuxirValueFieldsTest {
  @BeforeClass
  public static void beforeMatchVersion9() throws Exception {
    deleteCore(); // the one LuxirValueFieldsTest set up
    System.setProperty("tests.luceneMatchVersion", "9.0");
    init(true);
  }

  @AfterClass
  public static void afterMatchVersion9() {
    System.clearProperty("tests.luceneMatchVersion");
  }
}
