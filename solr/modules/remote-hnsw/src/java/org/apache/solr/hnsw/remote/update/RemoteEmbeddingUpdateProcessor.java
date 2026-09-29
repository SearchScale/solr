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
package org.apache.solr.hnsw.remote.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.SolrException;
import org.apache.solr.hnsw.remote.RemoteEmbeddingClient;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;

/**
 * Batches docs that need embedding and sends one {@code /v1/embed} call per {@code batchSize}.
 * Forwards to {@code next} in original order.
 */
final class RemoteEmbeddingUpdateProcessor extends UpdateRequestProcessor {
  private final RemoteEmbeddingClient client;
  private final String model;
  private final String sourceField;
  private final String targetField;
  private final int batchSize;
  private final List<Pending> pending = new ArrayList<>();

  RemoteEmbeddingUpdateProcessor(
      UpdateRequestProcessor next,
      RemoteEmbeddingClient client,
      String model,
      String sourceField,
      String targetField,
      int batchSize) {
    super(next);
    this.client = client;
    this.model = model;
    this.sourceField = sourceField;
    this.targetField = targetField;
    this.batchSize = batchSize;
  }

  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    Object rawValue = cmd.solrDoc == null ? null : cmd.solrDoc.getFieldValue(sourceField);
    String text = rawValue == null ? null : rawValue.toString();
    // AddUpdateCommand is reused across docs in a request; snapshot it.
    pending.add(
        new Pending(
            (AddUpdateCommand) cmd.clone(), (text == null || text.isBlank()) ? null : text));
    if (pending.size() >= batchSize) {
      flush();
    }
  }

  @Override
  public void finish() throws IOException {
    flush();
    super.finish();
  }

  private void flush() throws IOException {
    if (pending.isEmpty()) {
      return;
    }
    List<String> texts = new ArrayList<>();
    List<Integer> textIndices = new ArrayList<>();
    for (int i = 0; i < pending.size(); i++) {
      String text = pending.get(i).text;
      if (text != null) {
        textIndices.add(i);
        texts.add(text);
      }
    }

    if (!texts.isEmpty()) {
      float[][] vectors;
      try {
        vectors = client.embed(modelFor(pending.get(0)), texts);
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Remote embedding call failed for " + texts.size() + " document(s): " + e.getMessage(),
            e);
      }
      for (int i = 0; i < textIndices.size(); i++) {
        Pending p = pending.get(textIndices.get(i));
        p.cmd.solrDoc.setField(targetField, vectors[i]);
      }
    }

    List<Pending> batch = new ArrayList<>(pending);
    pending.clear();
    for (Pending p : batch) {
      super.processAdd(p.cmd);
    }
  }

  private String modelFor(Pending pending) {
    if (pending.cmd.getReq() != null) {
      String override = pending.cmd.getReq().getParams().get("model");
      if (override != null && !override.isBlank()) {
        return override;
      }
    }
    return model;
  }

  private static final class Pending {
    final AddUpdateCommand cmd;
    final String text;

    Pending(AddUpdateCommand cmd, String text) {
      this.cmd = cmd;
      this.text = text;
    }
  }
}
