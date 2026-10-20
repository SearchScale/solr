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

Apache Solr Luxir Codec
=======================

Stores Solr's fields in the segment format of [Luxir](https://github.com/luxir-search/luxir), a C++
search engine, inside Lucene's segment files, and runs queries, sorts and facets natively through
luxir-lucene and the Java Foreign Function and
Memory API.

- `LuxirCodecFactory`, `LuxirTextField`, `LuxirStrField` and the `Luxir*PointField` types
- `LuxirCodecQueryComponent`: the query, its filters and a field sort in one native call
- `LuxirFacetComponent` and `LuxirFacetModule`: classic and JSON facets counted natively
- `LuxirUpdateRequestHandler` and `ParallelFlushUpdateProcessorFactory`: faster indexing

The module needs JDK 22 or later, the luxir-lucene jar (`-Pluxir.lucene.jar`, copied into the
module's `lib` directory when deployed) and its native library (`-Pluxir.lucene.library`, passed
to Solr as `-Dluxir.lucene.library`). Start Solr with `--enable-native-access=ALL-UNNAMED`.
See the luxir-lucene README for configuration and benchmarks.
