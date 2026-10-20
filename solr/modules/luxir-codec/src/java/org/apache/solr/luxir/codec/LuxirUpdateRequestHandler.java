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

import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.UpdateRequestHandler;
import org.apache.solr.handler.loader.ContentStreamLoader;

/**
 * Solr's update handler with {@link FastJsonDocsLoader} for JSON bodies. Where the update chain
 * allows it, documents are built without a {@code SolrInputDocument} ({@link DirectDocBuilder});
 * {@code <bool name="directDocuments">false</bool>} turns that off.
 *
 * <pre>
 * &lt;requestHandler name="/update" class="org.apache.solr.luxir.codec.LuxirUpdateRequestHandler"/&gt;
 * </pre>
 */
public class LuxirUpdateRequestHandler extends UpdateRequestHandler {
  @Override
  protected Map<String, ContentStreamLoader> createDefaultLoaders(NamedList<?> args) {
    Map<String, ContentStreamLoader> loaders = new HashMap<>(super.createDefaultLoaders(args));
    Object direct = args == null ? null : args.get("directDocuments");
    FastJsonDocsLoader fast =
        new FastJsonDocsLoader(
            loaders.get("application/json"),
            direct == null || Boolean.parseBoolean(direct.toString()));
    loaders.put("application/json", fast);
    loaders.put("text/json", fast);
    return loaders;
  }
}
