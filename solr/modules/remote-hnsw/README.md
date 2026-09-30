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

GPU service
-----------

The service is already running on the 4090. Reach it over Tailscale as
`4090-workstation` (MagicDNS). Solr stays on your machine.

    export SOLR_HNSW_BUILD_SERVICE_URL=http://4090-workstation:18765
    curl -sS "$SOLR_HNSW_BUILD_SERVICE_URL/v1/health"

You want `"gpu":true`. Quick check that indexing works (200 random 8-d
vectors, skipped unless the env var is set):

    ./gradlew :solr:modules:remote-hnsw:test --tests ManualRemoteServiceSmokeTest \
      -Ptests.useSecurityManager=false

Index some vectors
------------------

Full Solr install, not slim. From this checkout:

    ./gradlew -p solr/packaging devFull

    export SOLR_MODULES=remote-hnsw
    export SOLR_HNSW_BUILD_SERVICE_URL=http://4090-workstation:18765
    solr/packaging/build/dev/bin/solr start

    solr/packaging/build/dev/bin/solr create -c vectors \
      -d "$PWD/solr/modules/remote-hnsw/example"

`example/conf` is an 8-d `remote_hnsw` configset. `serviceUrl` is empty so
it reads the env var. Then:

    curl -X POST -H 'Content-type:application/json' \
      'http://localhost:8983/solr/vectors/update?commit=true' \
      --data-binary @"$PWD/solr/modules/remote-hnsw/example/sample-docs.json"

    curl -s 'http://localhost:8983/solr/vectors/select' \
      --data-urlencode 'q={!knn f=vector topK=3}[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8]' \
      --data-urlencode 'fl=id,score'

Change `vectorDimension` in `example/conf/schema.xml` if your vectors are
not 8-d (commit after the first add is what hits the GPU).

* `SOLR_HNSW_BUILD_SERVICE_URL` — required for indexing
* `SOLR_HNSW_BUILD_TIMEOUT_SECONDS` — default 600; raise for large segments
* `SOLR_HNSW_BUILD_BUILDER` — `auto` (default), `gpu`, or `cpu`
* `SOLR_HNSW_BUILD_SERVICE_TOKEN` — sent as `Authorization: Bearer` if set

`builder` is sent as `X-RHNSW-Builder`. `fallbackToCpu=true` on the codec
runs Lucene CPU HNSW on the Solr node if the HTTP call fails. Default is
to fail the flush.

Large batches spend most of their time on the HTTP transfer, not CAGRA.

Unit tests (mock HTTP, no GPU): `./gradlew :solr:modules:remote-hnsw:test`

Embedding (optional)
--------------------

Same process, different endpoint. Text field → `/v1/embed` → vector field,
then the codec above runs as usual. If `solr.embed.service.url` is unset,
the HNSW service URL is reused. Start from
`src/test-files/solr/embed-collection/conf`.

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

`hash-demo` is always registered (no weights). Real models are ONNX dirs on
the service host (`--models-dir`). `GET /v1/health` lists ids.

Protocol
--------

* `GET /v1/health`
* `POST /v1/hnsw/build` — little-endian FLOAT32 body (`count * dim * 4`);
  metadata in `X-RHNSW-*` headers; response is an `RHNW` neighbor list, not
  a Lucene index.
* `POST /v1/embed` — length-prefixed UTF-8 texts; response is FLOAT32 plus
  `X-Embed-Dimension`.

Start the service (4090)
------------------------

On the 4090, from `/data2/hnsw-service`:

    conda activate cuvs-solr
    export LD_LIBRARY_PATH="$CONDA_PREFIX/lib:${LD_LIBRARY_PATH:-}:/usr/local/cuda/lib64"
    java --enable-native-access=ALL-UNNAMED \
      -Djava.library.path="$CONDA_PREFIX/lib:/usr/local/cuda/lib64" \
      -cp "solr-remote-hnsw-service.jar:lib/*" \
      org.apache.solr.hnsw.remote.service.HnswBuildServer \
      --port 18765 --bind 100.101.239.76
