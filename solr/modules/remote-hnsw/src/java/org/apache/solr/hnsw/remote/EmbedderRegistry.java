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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Model id → {@link TextEmbedder}. Loaded lazily on first use. */
public final class EmbedderRegistry {
  private final Map<String, Supplier<TextEmbedder>> factories = new ConcurrentHashMap<>();
  private final Map<String, TextEmbedder> loaded = new ConcurrentHashMap<>();

  /** Registers a model id. The supplier runs at most once, on first use. */
  public void register(String modelId, Supplier<TextEmbedder> factory) {
    factories.put(modelId, factory);
  }

  public TextEmbedder resolve(String modelId) {
    TextEmbedder cached = loaded.get(modelId);
    if (cached != null) {
      return cached;
    }
    Supplier<TextEmbedder> factory = factories.get(modelId);
    if (factory == null) {
      throw new IllegalArgumentException(
          "unknown embedding model id: " + modelId + " (known: " + factories.keySet() + ")");
    }
    return loaded.computeIfAbsent(modelId, id -> factory.get());
  }

  /** Registered model ids (not necessarily loaded yet). */
  public Set<String> modelIds() {
    return new TreeSet<>(factories.keySet());
  }

  /** Registry with {@code hash-demo} already registered. */
  public static EmbedderRegistry withHashDemoModel(int dimension) {
    EmbedderRegistry registry = new EmbedderRegistry();
    registry.register("hash-demo", () -> new HashTextEmbedder(dimension));
    return registry;
  }
}
