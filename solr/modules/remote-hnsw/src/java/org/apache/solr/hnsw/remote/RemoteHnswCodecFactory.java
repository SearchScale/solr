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

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.solr.common.util.EnvUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CodecFactory;
import org.apache.solr.core.SchemaCodecFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.util.plugin.SolrCoreAware;

/** Codec factory that sends uncommitted vectors to a remote HNSW build service at flush. */
public class RemoteHnswCodecFactory extends CodecFactory implements SolrCoreAware {

  public static final String SERVICE_URL_ARG = "serviceUrl";
  public static final String TIMEOUT_SECONDS_ARG = "timeoutSeconds";
  public static final String FALLBACK_TO_CPU_ARG = "fallbackToCpu";
  public static final String SERVICE_TOKEN_ARG = "serviceToken";
  public static final String BUILDER_ARG = "builder";

  public static final String SERVICE_URL_PROP = "solr.hnsw.build.service.url";
  public static final String TIMEOUT_PROP = "solr.hnsw.build.timeout.seconds";
  public static final String TOKEN_PROP = "solr.hnsw.build.service.token";
  public static final String BUILDER_PROP = "solr.hnsw.build.builder";

  /** Config arg if set, otherwise {@code SOLR_HNSW_BUILD_SERVICE_URL}. */
  public static String resolveBuildServiceUrl(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String fromEnv = EnvUtils.getProperty(SERVICE_URL_PROP);
    return (fromEnv == null || fromEnv.isBlank()) ? null : fromEnv;
  }

  /** Config, then {@code SOLR_EMBED_SERVICE_URL}, then the HNSW build URL. */
  public static String resolveEmbedServiceUrl(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String embed = EnvUtils.getProperty(EmbedProtocol.EMBED_SERVICE_URL_PROP);
    if (embed != null && !embed.isBlank()) {
      return embed;
    }
    return resolveBuildServiceUrl(null);
  }

  private final SchemaCodecFactory fallback;
  private SolrCore core;
  private NamedList<?> args;
  private RemoteHnswCodec codec;

  public RemoteHnswCodecFactory() {
    this.fallback = new SchemaCodecFactory();
  }

  @Override
  public void init(NamedList<?> args) {
    fallback.init(args);
    this.args = args;
  }

  @Override
  public void inform(SolrCore solrCore) {
    fallback.inform(solrCore);
    this.core = solrCore;
  }

  @Override
  public Codec getCodec() {
    if (codec == null) {
      Lucene104Codec fallbackCodec = (Lucene104Codec) fallback.getCodec();
      String serviceUrl = resolveBuildServiceUrl(stringArg(SERVICE_URL_ARG, null));
      int timeoutSeconds =
          intArg(TIMEOUT_SECONDS_ARG, EnvUtils.getPropertyAsInteger(TIMEOUT_PROP, 600));
      String token = stringArg(SERVICE_TOKEN_ARG, EnvUtils.getProperty(TOKEN_PROP));
      boolean fallbackToCpu = boolArg(FALLBACK_TO_CPU_ARG, false);
      HnswBuilder builder =
          HnswBuilder.parse(
              stringArg(
                  BUILDER_ARG, EnvUtils.getProperty(BUILDER_PROP, HnswBuilder.AUTO.wireName())));
      RemoteHnswClient client =
          (serviceUrl == null || serviceUrl.isBlank())
              ? null
              : new RemoteHnswClient(serviceUrl, token, timeoutSeconds);
      codec = new RemoteHnswCodec(core, fallbackCodec, client, fallbackToCpu, builder);
    }
    return codec;
  }

  private String stringArg(String name, String dflt) {
    Object v = args == null ? null : args.get(name);
    if (v == null) {
      return dflt;
    }
    String s = v.toString();
    return s.isBlank() ? dflt : s;
  }

  private int intArg(String name, Integer dflt) {
    Object v = args == null ? null : args.get(name);
    if (v == null) {
      return dflt == null ? 600 : dflt;
    }
    if (v instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(v.toString());
  }

  private boolean boolArg(String name, boolean dflt) {
    Object v = args == null ? null : args.get(name);
    if (v == null) {
      return dflt;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(v.toString());
  }
}
