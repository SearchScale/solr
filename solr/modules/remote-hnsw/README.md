<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

Remote HNSW
===========

At flush, Solr POSTs FLOAT32 vectors to a GPU build service, gets an HNSW
neighbor list back, and writes Lucene 99 `.vem` / `.vex` locally. knn search
is ordinary Lucene on the Solr node.

Not a replacement for the in-process `cuvs` module. Experimental.

The service URL is not hardcoded. Set `SOLR_HNSW_BUILD_SERVICE_URL` to the
host already running `HnswBuildServer`.

Try it
------

The GPU service should already be up. From this Solr checkout:

    export SOLR_HNSW_BUILD_SERVICE_URL=http://<gpu-host>:18765
    curl -sS "$SOLR_HNSW_BUILD_SERVICE_URL/v1/health"

You want `"gpu":true`. Then:

    ./gradlew :solr:modules:remote-hnsw:test --tests ManualRemoteServiceSmokeTest

That indexes 200 vectors through the live service and checks knn. Skipped
unless the env var is set.

Same host, text → vector → graph (`hash-demo`, no ONNX):

    ./gradlew :solr:modules:remote-hnsw:test --tests ManualRemoteEmbeddingSmokeTest

Unit tests (mock HTTP, no GPU):

    ./gradlew :solr:modules:remote-hnsw:test

Solr node
---------

Full Solr install, not slim:

    export SOLR_MODULES=remote-hnsw
    export SOLR_HNSW_BUILD_SERVICE_URL=http://<gpu-host>:18765
    bin/solr start

Start from `src/test-files/solr/collection1/conf` (`vectorDimension` is 8;
change it to match your data). `serviceUrl` is empty on purpose so it reads
the env var.

    <codecFactory class="org.apache.solr.hnsw.remote.RemoteHnswCodecFactory">
      <str name="serviceUrl">${solr.hnsw.build.service.url:}</str>
      <int name="timeoutSeconds">${solr.hnsw.build.timeout.seconds:600}</int>
      <str name="builder">${solr.hnsw.build.builder:auto}</str>
    </codecFactory>

    <fieldType name="knn_vector" class="solr.DenseVectorField" vectorDimension="8"
               knnAlgorithm="remote_hnsw" similarityFunction="euclidean"/>

Index docs with a `vector` field. Search with `{!knn f=vector topK=10}[...]`.

* `SOLR_HNSW_BUILD_SERVICE_URL` — required for indexing
* `SOLR_HNSW_BUILD_TIMEOUT_SECONDS` — default 600; raise for large segments
* `SOLR_HNSW_BUILD_BUILDER` — `auto` (default), `gpu`, or `cpu`
* `SOLR_HNSW_BUILD_SERVICE_TOKEN` — sent as `Authorization: Bearer` if set

`builder` is sent as `X-RHNSW-Builder`. `fallbackToCpu=true` on the codec is
different: Lucene CPU HNSW on the Solr node if the HTTP call fails. Default
is to fail the flush.

Large batches spend most of their time on the HTTP transfer, not CAGRA.
1M × 768 is about 3 GiB on the wire.

Embedding (optional)
--------------------

Same process, different endpoint. Text field → `/v1/embed` → vector field,
then the codec above runs as usual. If `solr.embed.service.url` is unset,
the HNSW service URL is reused.

    <processor class="org.apache.solr.hnsw.remote.update.RemoteEmbeddingUpdateProcessorFactory">
      <str name="serviceUrl">${solr.embed.service.url:}</str>
      <str name="sourceField">content</str>
      <str name="targetField">vector</str>
      <str name="model">hash-demo</str>
      <int name="batchSize">500</int>
    </processor>

    <queryParser name="remote_embed" class="org.apache.solr.hnsw.remote.search.RemoteEmbeddingQParserPlugin">
      <str name="serviceUrl">${solr.embed.service.url:}</str>
      <str name="model">hash-demo</str>
    </queryParser>

Query: `q={!remote_embed f=vector topK=5}quick brown fox`

Docs missing `sourceField` pass through with no vector. A failed embed call
rejects the whole batch.

`hash-demo` is always registered (no weights). Real models are ONNX dirs on
the service host (`--models-dir`, each subdir with `model.onnx` +
`tokenizer.json`). `GET /v1/health` lists ids. Schema `vectorDimension` must
match the model.

Protocol
--------

* `GET /v1/health`
* `POST /v1/hnsw/build` — little-endian FLOAT32 body (`count * dim * 4`);
  metadata in `X-RHNSW-*` headers; response is an `RHNW` neighbor list, not
  a Lucene index. `X-RHNSW-Builder-Used` says which path ran.
* `POST /v1/embed` — length-prefixed UTF-8 texts; `X-Embed-Model` /
  `X-Embed-Count`; response is FLOAT32 plus `X-Embed-Dimension`.

Restarting the service
----------------------

JDK 22+, CUDA 13+, `libcuvs` on the library path. From this tree:

    ./gradlew :solr:modules:remote-hnsw:serviceDist

Copy `solr/modules/remote-hnsw/build/distributions/remote-hnsw-service/` to
the GPU host, then:

    java --enable-native-access=ALL-UNNAMED -Xms8g -Xmx16g \
      -Djava.io.tmpdir=/path/to/scratch \
      -Djava.library.path=/path/to/cuvs/lib:/usr/local/cuda/lib64 \
      -cp "solr-remote-hnsw-service.jar:lib/*" \
      org.apache.solr.hnsw.remote.service.HnswBuildServer \
      --port 18765 --bind <address-solr-can-reach>

`--builder auto|gpu|cpu`. Bind to the interface Solr uses; do not publish an
unauthenticated listener on a public NIC. Skip `--models-dir` unless you
need ONNX (weights compete with CAGRA for GPU memory).
