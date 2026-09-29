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

import org.apache.solr.SolrTestCase;
import org.junit.Test;

public class HnswBuilderTest extends SolrTestCase {

  @Test
  public void testParseAliases() {
    assertEquals(HnswBuilder.AUTO, HnswBuilder.parse(null));
    assertEquals(HnswBuilder.AUTO, HnswBuilder.parse("auto"));
    assertEquals(HnswBuilder.GPU, HnswBuilder.parse("gpu"));
    assertEquals(HnswBuilder.GPU, HnswBuilder.parse("CAGRA"));
    assertEquals(HnswBuilder.CPU, HnswBuilder.parse("cpu"));
    assertEquals(HnswBuilder.CPU, HnswBuilder.parse("lucene"));
    expectThrows(IllegalArgumentException.class, () -> HnswBuilder.parse("tpu"));
  }

  @Test
  public void testResolve() {
    assertEquals(HnswBuilder.GPU, HnswBuilder.AUTO.resolve(HnswBuilder.AUTO, true));
    assertEquals(HnswBuilder.CPU, HnswBuilder.AUTO.resolve(HnswBuilder.AUTO, false));
    assertEquals(HnswBuilder.CPU, HnswBuilder.AUTO.resolve(HnswBuilder.CPU, true));
    assertEquals(HnswBuilder.GPU, HnswBuilder.AUTO.resolve(HnswBuilder.GPU, true));
    assertEquals(HnswBuilder.CPU, HnswBuilder.CPU.resolve(HnswBuilder.GPU, true));
    assertEquals(HnswBuilder.GPU, HnswBuilder.GPU.resolve(HnswBuilder.CPU, false));
  }
}
