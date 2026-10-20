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

import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.search.facet.FacetAccelerator;
import org.apache.solr.search.facet.FacetModule;

/**
 * The JSON Facet API ({@code json.facet}) with document collection by Luxir where the fields' doc
 * values are native (see {@link LuxirFacetAccelerator}); results are the same as {@link
 * FacetModule}'s. Register it as the {@code facet_module} component:
 *
 * <pre>
 * &lt;searchComponent name="facet_module" class="org.apache.solr.luxir.codec.LuxirFacetModule"/&gt;
 * </pre>
 *
 * A request with {@code luxirCodec=false} collects in Java.
 */
public class LuxirFacetModule extends FacetModule {
  @Override
  protected FacetAccelerator accelerator(ResponseBuilder rb) {
    return rb.req.getParams().getBool(LuxirCodecQueryComponent.COMPONENT_NAME, true)
        ? LuxirFacetAccelerator.INSTANCE
        : null;
  }
}
