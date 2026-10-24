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

import static org.apache.solr.common.params.CommonParams.PATH;

import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.UpdateRequestHandler;
import org.apache.solr.handler.loader.ContentStreamLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.update.processor.UpdateRequestProcessor;

/**
 * Solr's update handler with {@link FastJsonDocsLoader} for JSON bodies, including those sent to
 * {@code /update/json} and {@code /update/json/docs} (register it under those names too to replace
 * Solr's implicit handlers). Where the update chain allows it, documents are built without a {@code
 * SolrInputDocument} ({@link DirectDocBuilder}); {@code <bool name="directDocuments">false</bool>}
 * turns that off.
 *
 * <pre>
 * &lt;requestHandler name="/update" class="org.apache.solr.luxir.codec.LuxirUpdateRequestHandler"/&gt;
 * &lt;requestHandler name="/update/json/docs" class="org.apache.solr.luxir.codec.LuxirUpdateRequestHandler"&gt;
 *   &lt;lst name="invariants"&gt;
 *     &lt;str name="update.contentType"&gt;application/json&lt;/str&gt;
 *     &lt;str name="json.command"&gt;false&lt;/str&gt;
 *   &lt;/lst&gt;
 * &lt;/requestHandler&gt;
 * </pre>
 */
public class LuxirUpdateRequestHandler extends UpdateRequestHandler {
  private FastJsonDocsLoader fast;

  @Override
  protected Map<String, ContentStreamLoader> createDefaultLoaders(NamedList<?> args) {
    Map<String, ContentStreamLoader> loaders = new HashMap<>(super.createDefaultLoaders(args));
    Object direct = args == null ? null : args.get("directDocuments");
    fast =
        new FastJsonDocsLoader(
            loaders.get("application/json"),
            direct == null || Boolean.parseBoolean(direct.toString()));
    loaders.put("application/json", fast);
    loaders.put("text/json", fast);
    return loaders;
  }

  @Override
  protected ContentStreamLoader newLoader(SolrQueryRequest req, UpdateRequestProcessor processor) {
    // Solr picks its own JSON loader by path, before the content type, for these two paths
    Object path = req.getContext().get(PATH);
    if (JSON_PATH.equals(path) || DOC_PATH.equals(path)) return fast;
    return super.newLoader(req, processor);
  }
}
