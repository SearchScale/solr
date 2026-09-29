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

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.hnsw.remote.TextEmbedder;

/**
 * Sentence-transformer embedder on the service host. Expects a HuggingFace ONNX export directory
 * with {@code model.onnx} and {@code tokenizer.json}.
 */
final class OnnxTextEmbedder implements TextEmbedder {
  private final OrtEnvironment env;
  private final OrtSession session;
  private final HuggingFaceTokenizer tokenizer;
  private final boolean useTokenTypeIds;
  private final int dimension;

  OnnxTextEmbedder(Path modelDir) throws OrtException, IOException {
    Path onnx = modelDir.resolve("model.onnx");
    Path tokenizerJson = modelDir.resolve("tokenizer.json");
    if (!Files.isRegularFile(onnx) || !Files.isRegularFile(tokenizerJson)) {
      throw new IOException(
          "ONNX model directory must contain model.onnx and tokenizer.json: " + modelDir);
    }
    env = OrtEnvironment.getEnvironment();
    session = env.createSession(onnx.toString(), new OrtSession.SessionOptions());
    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson);
    useTokenTypeIds = session.getInputNames().contains("token_type_ids");
    NodeInfo out = session.getOutputInfo().values().iterator().next();
    long[] shape = ((TensorInfo) out.getInfo()).getShape();
    dimension = (int) shape[shape.length - 1];
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public float[][] embedBatch(List<String> texts) {
    float[][] vectors = new float[texts.size()][];
    for (int i = 0; i < texts.size(); i++) {
      try {
        vectors[i] = encode(texts.get(i) == null ? "" : texts.get(i));
      } catch (OrtException e) {
        throw new IllegalStateException("ONNX embed failed: " + e.getMessage(), e);
      }
    }
    return vectors;
  }

  private float[] encode(String text) throws OrtException {
    Encoding enc = tokenizer.encode(text);
    long[] ids = enc.getIds();
    long[] mask = enc.getAttentionMask();
    long[] shape = {1, ids.length};

    Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
      inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
      if (useTokenTypeIds) {
        inputs.put(
            "token_type_ids",
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.getTypeIds()), shape));
      }
      try (OrtSession.Result result = session.run(inputs)) {
        float[][][] output = (float[][][]) result.get(0).getValue();
        return normalize(meanPool(output[0], mask));
      }
    } finally {
      for (OnnxTensor tensor : inputs.values()) {
        tensor.close();
      }
    }
  }

  private float[] meanPool(float[][] tokens, long[] mask) {
    float[] pooled = new float[dimension];
    int count = 0;
    for (int i = 0; i < tokens.length; i++) {
      if (mask[i] == 1) {
        for (int j = 0; j < dimension; j++) {
          pooled[j] += tokens[i][j];
        }
        count++;
      }
    }
    if (count > 0) {
      for (int j = 0; j < dimension; j++) {
        pooled[j] /= count;
      }
    }
    return pooled;
  }

  private float[] normalize(float[] vector) {
    double sumSquares = 0;
    for (float v : vector) {
      sumSquares += (double) v * v;
    }
    if (sumSquares == 0) {
      return vector;
    }
    float norm = (float) Math.sqrt(sumSquares);
    float[] out = new float[dimension];
    for (int i = 0; i < dimension; i++) {
      out[i] = vector[i] / norm;
    }
    return out;
  }
}
