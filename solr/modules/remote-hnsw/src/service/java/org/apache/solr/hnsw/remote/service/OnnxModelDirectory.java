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
package org.apache.solr.hnsw.remote.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.apache.solr.hnsw.remote.EmbedderRegistry;

/**
 * Registers ONNX export dirs ({@code model.onnx} + {@code tokenizer.json}) on {@link
 * EmbedderRegistry}.
 */
final class OnnxModelDirectory {
  static final Map<String, String> WELL_KNOWN_ALIASES =
      Map.of(
          "onnx_model", "minilm",
          "onnx_model_mpnet", "mpnet",
          "onnx_model_minilm_l12", "minilm_l12",
          "onnx_model_multilingual", "multilingual",
          "all-minilm-l6-v2", "minilm",
          "all-mpnet-base-v2", "mpnet",
          "all-minilm-l12-v2", "minilm_l12",
          "paraphrase-multilingual-minilm-l12-v2", "multilingual");

  private OnnxModelDirectory() {}

  static int registerAll(EmbedderRegistry registry, Path modelsDir) throws IOException {
    if (!Files.isDirectory(modelsDir)) {
      throw new IOException("models directory does not exist: " + modelsDir);
    }
    TreeSet<String> registered = new TreeSet<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelsDir)) {
      for (Path child : stream) {
        if (!Files.isDirectory(child) || !isOnnxExport(child)) {
          continue;
        }
        String dirName = child.getFileName().toString();
        register(registry, dirName, child, registered);
        String alias = WELL_KNOWN_ALIASES.get(dirName.toLowerCase(Locale.ROOT));
        if (alias != null && !alias.equals(dirName)) {
          register(registry, alias, child, registered);
        }
      }
    }
    return registered.size();
  }

  static boolean isOnnxExport(Path dir) {
    return Files.isRegularFile(dir.resolve("model.onnx"))
        && Files.isRegularFile(dir.resolve("tokenizer.json"));
  }

  private static void register(
      EmbedderRegistry registry, String modelId, Path dir, TreeSet<String> registered) {
    Path modelDir = dir;
    registry.register(
        modelId,
        () -> {
          try {
            return new OnnxTextEmbedder(modelDir);
          } catch (Exception e) {
            throw new IllegalStateException(
                "failed to load ONNX model '"
                    + modelId
                    + "' from "
                    + modelDir
                    + ": "
                    + e.getMessage(),
                e);
          }
        });
    registered.add(modelId);
  }
}
