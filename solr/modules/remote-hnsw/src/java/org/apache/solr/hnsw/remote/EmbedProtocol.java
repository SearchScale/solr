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

/** {@code POST /v1/embed}: texts in, FLOAT32 vectors out. */
public final class EmbedProtocol {
  public static final String EMBED_HEALTH_PATH = "/v1/embed/health";
  public static final String EMBED_PATH = "/v1/embed";
  public static final String EMBED_SERVICE_URL_PROP = "solr.embed.service.url";

  /** Model id on the service, e.g. {@code mpnet}. Weights stay on the service host. */
  public static final String HDR_EMBED_MODEL = "X-Embed-Model";

  public static final String HDR_EMBED_KIND = "X-Embed-Kind";

  public static final String HDR_EMBED_COUNT = "X-Embed-Count";

  /** Dimension the server produced; check this against the schema. */
  public static final String HDR_EMBED_DIMENSION = "X-Embed-Dimension";

  public static final String KIND_TEXT = "text";

  public static final String CONTENT_TYPE_EMBED_ITEMS = "application/x-rhnsw-embed-items";

  private EmbedProtocol() {}
}
