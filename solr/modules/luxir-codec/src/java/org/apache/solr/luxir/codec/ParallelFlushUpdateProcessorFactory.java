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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.lucene.index.IndexWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.RefCounted;

/**
 * Before a hard commit, flushes the IndexWriter's buffered segments in parallel ({@link
 * IndexWriter#flushNextBuffer()} from several threads), so the commit itself, which flushes
 * whatever is still buffered on one thread, has little left to do.
 *
 * <pre>
 * &lt;processor class="org.apache.solr.luxir.codec.ParallelFlushUpdateProcessorFactory"&gt;
 *   &lt;int name="threads"&gt;8&lt;/int&gt;   &lt;!-- default: available processors --&gt;
 * &lt;/processor&gt;
 * </pre>
 */
public class ParallelFlushUpdateProcessorFactory extends UpdateRequestProcessorFactory {
  private int threads = Runtime.getRuntime().availableProcessors();

  @Override
  public void init(NamedList<?> args) {
    Object t = args.get("threads");
    if (t != null) threads = Integer.parseInt(t.toString());
  }

  @Override
  public UpdateRequestProcessor getInstance(
      SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
    return new UpdateRequestProcessor(next) {
      @Override
      public void processCommit(CommitUpdateCommand cmd) throws IOException {
        if (!cmd.softCommit && threads > 1) flushInParallel(req);
        super.processCommit(cmd);
      }
    };
  }

  private void flushInParallel(SolrQueryRequest req) throws IOException {
    RefCounted<IndexWriter> ref = req.getCore().getSolrCoreState().getIndexWriter(req.getCore());
    ExecutorService pool =
        ExecutorUtil.newMDCAwareFixedThreadPool(
            threads, new SolrNamedThreadFactory("luxirParallelFlush"));
    try {
      IndexWriter writer = ref.get();
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        tasks.add(
            pool.submit(
                () -> {
                  while (writer.flushNextBuffer()) {} // each call flushes one buffered segment
                  return null;
                }));
      }
      for (Future<?> f : tasks) f.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e.getCause());
    } finally {
      pool.shutdown();
      ref.decref();
    }
  }
}
