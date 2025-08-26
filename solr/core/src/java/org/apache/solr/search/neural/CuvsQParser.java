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
package org.apache.solr.search.neural;

import com.nvidia.cuvs.lucene.GPUKnnFloatVectorQuery;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CuvsQParser extends AbstractVectorQParserBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public CuvsQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  public Query parse() throws SyntaxError {
    // ?q={!cuvs f=vector topK=32 cagraITopK=1 cagraSearchWidth=5 }[1.0, 2.0, 3.0, 4.0]
    if (qstr == null)
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Missing float values ");

    List<?> vals = null;
    try {
      vals = (List<?>) Utils.fromJSONString(qstr);
    } catch (Exception e) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Invalid format for value. should be a float[] " + qstr);
    }
    float[] floats = new float[vals.size()];
    for (int i = 0; i < vals.size(); i++) {
      Object o = vals.get(i);
      floats[i] = Float.parseFloat(o.toString());
    }

    query =
        new GPUKnnFloatVectorQuery(
            localParams.get("f"),
            floats,
            parseIntVal("topK"),
            null,
            parseIntVal("cagraITopK"),
            parseIntVal("cagraSearchWidth"));
    return query;
  }

  private int parseIntVal(String name) {
    String s = localParams.get(name);
    if (s == null) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST, "Missing required localparam : " + name);
    }
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST, "Invalid value " + s + " for localparam : " + name);
    }
  }
}
