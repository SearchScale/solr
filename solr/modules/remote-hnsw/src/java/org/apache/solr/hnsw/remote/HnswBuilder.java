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

import java.util.Locale;

/**
 * Which graph builder the HNSW service should use. {@link #AUTO} means GPU CAGRA when the host
 * supports cuVS, otherwise Lucene CPU HNSW.
 */
public enum HnswBuilder {
  AUTO,
  GPU,
  CPU;

  public static HnswBuilder parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return AUTO;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "auto" -> AUTO;
      case "gpu", "cagra" -> GPU;
      case "cpu", "lucene" -> CPU;
      default ->
          throw new IllegalArgumentException(
              "Unknown HNSW builder '" + raw + "'; expected auto, gpu, or cpu");
    };
  }

  /**
   * Request wins unless it is {@link #AUTO}, in which case {@code serverDefault} is used. A
   * remaining {@link #AUTO} becomes {@link #GPU} when cuVS is available, otherwise {@link #CPU}.
   */
  public HnswBuilder resolve(HnswBuilder serverDefault, boolean gpuAvailable) {
    HnswBuilder mode = this == AUTO ? serverDefault : this;
    if (mode == AUTO) {
      return gpuAvailable ? GPU : CPU;
    }
    return mode;
  }

  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
