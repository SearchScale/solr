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
package org.apache.solr.cuvs;

import com.nvidia.cuvs.CagraIndexParams.CagraGraphBuildAlgo;
import com.nvidia.cuvs.CagraIndexParams.CodebookGen;
import com.nvidia.cuvs.CagraIndexParams.CudaDataType;
import com.nvidia.cuvs.CagraIndexParams.CuvsDistanceType;
import com.nvidia.cuvs.CuVSIvfPqIndexParams;
import com.nvidia.cuvs.CuVSIvfPqParams;
import com.nvidia.cuvs.CuVSIvfPqSearchParams;
import com.nvidia.cuvs.lucene.AcceleratedHNSWParams;
import com.nvidia.cuvs.lucene.Lucene99AcceleratedHNSWVectorsFormat;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.DenseVectorField;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This codec utilizes the Lucene99AcceleratedHNSWVectorsFormat from the lucene-cuvs library to
 * enable GPU-based accelerated vector search.
 *
 * @since 10.0.0
 */
public class CuVSCodec extends FilterCodec {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String FALLBACK_CODEC = "Lucene104";
  private static volatile boolean cudaRuntimeLoaded = false;
  private final SolrCore core;
  private final Lucene104Codec fallbackCodec;

  public CuVSCodec(SolrCore core, Lucene104Codec fallback, NamedList<?> args) {
    super(FALLBACK_CODEC, fallback);
    this.core = core;
    this.fallbackCodec = fallback;
  }

  @Override
  public KnnVectorsFormat knnVectorsFormat() {
    return perFieldKnnVectorsFormat;
  }

  private PerFieldKnnVectorsFormat perFieldKnnVectorsFormat =
      new PerFieldKnnVectorsFormat() {
        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
          final SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
          FieldType fieldType = (schemaField == null ? null : schemaField.getType());
          if (fieldType instanceof DenseVectorField vectorType) {
            String knnAlgorithm = vectorType.getKnnAlgorithm();
            if (DenseVectorField.CAGRA_HNSW_ALGORITHM.equals(knnAlgorithm)) {

              int cuvsWriterThreads = vectorType.getCuvsWriterThreads();
              int cuvsIntGraphDegree = vectorType.getCuvsIntGraphDegree();
              int cuvsGraphDegree = vectorType.getCuvsGraphDegree();
              int cuvsHnswLayers = vectorType.getCuvsHnswLayers();
              int cuvsHnswM = vectorType.getCuvsHnswMaxConn();
              int cuvsHnswEfConstruction = vectorType.getCuvsHnswEfConstruction();

              assert cuvsWriterThreads > 0 : "cuvsWriterThreads cannot be less then or equal to 0";
              assert cuvsIntGraphDegree > 0
                  : "cuvsIntGraphDegree cannot be less then or equal to 0";
              assert cuvsGraphDegree > 0 : "cuvsGraphDegree cannot be less then or equal to 0";
              assert cuvsHnswLayers > 0 : "cuvsHnswLayers cannot be less then or equal to 0";
              assert cuvsHnswM > 0 : "cuvsHnswM cannot be less then or equal to 0";
              assert cuvsHnswEfConstruction > 0
                  : "cuvsHnswEfConstruction cannot be less then or equal to 0";

              CagraGraphBuildAlgo buildAlgo =
                  CagraGraphBuildAlgo.valueOf(vectorType.getCuvsCagraGraphBuildAlgo());
              loadCudaRuntime();

              AcceleratedHNSWParams.Builder paramsBuilder =
                  new AcceleratedHNSWParams.Builder()
                      .withWriterThreads(cuvsWriterThreads)
                      .withIntermediateGraphDegree(cuvsIntGraphDegree)
                      .withGraphDegree(cuvsGraphDegree)
                      .withHNSWLayer(cuvsHnswLayers)
                      .withMaxConn(cuvsHnswM)
                      .withBeamWidth(cuvsHnswEfConstruction)
                      .withCagraGraphBuildAlgo(buildAlgo);
              if (buildAlgo == CagraGraphBuildAlgo.IVF_PQ) {
                CuVSIvfPqIndexParams ivfPqIndexParams =
                    new CuVSIvfPqIndexParams.Builder()
                        .withAddDataOnBuild(vectorType.getCuVSIvfPqIndexParamsAddDataOnBuild())
                        .withCodebookKind(
                            CodebookGen.valueOf(vectorType.getCuVSIvfPqIndexParamsCodebookKind()))
                        .withConservativeMemoryAllocation(
                            vectorType.getCuVSIvfPqIndexParamsConservativeMemoryAllocation())
                        .withForceRandomRotation(
                            vectorType.getCuVSIvfPqIndexParamsForceRandomRotation())
                        .withKmeansNIters(vectorType.getCuVSIvfPqIndexParamsKmeansNIters())
                        .withKmeansTrainsetFraction(
                            vectorType.getCuVSIvfPqIndexParamsKmeansTrainsetFraction())
                        .withMaxTrainPointsPerPqCode(
                            vectorType.getCuVSIvfPqIndexParamsMaxTrainPointsPerPqCode())
                        .withMetric(
                            CuvsDistanceType.valueOf(vectorType.getCuVSIvfPqIndexParamsMetric()))
                        .withMetricArg(vectorType.getCuVSIvfPqIndexParamsMetricArg())
                        .withNLists(vectorType.getCuVSIvfPqIndexParamsNLists())
                        .withPqBits(vectorType.getCuVSIvfPqIndexParamsPqBits())
                        .withPqDim(vectorType.getCuVSIvfPqIndexParamsPqDim())
                        .build();
                CuVSIvfPqSearchParams ivfPqSearchParams =
                    new CuVSIvfPqSearchParams.Builder()
                        .withInternalDistanceDtype(
                            CudaDataType.valueOf(
                                vectorType.getCuVSIvfPqSearchParamsInternalDistanceDtype()))
                        .withLutDtype(
                            CudaDataType.valueOf(vectorType.getCuVSIvfPqSearchParamsLutDtype()))
                        .withNProbes(vectorType.getCuVSIvfPqSearchParamsNProbes())
                        .withPreferredShmemCarveout(
                            vectorType.getCuVSIvfPqSearchParamsPreferredShmemCarveout())
                        .build();
                paramsBuilder.withCuVSIvfPqParams(
                    new CuVSIvfPqParams.Builder()
                        .withCuVSIvfPqIndexParams(ivfPqIndexParams)
                        .withCuVSIvfPqSearchParams(ivfPqSearchParams)
                        .withRefinementRate(vectorType.getCuVSIvfPqParamsRefinementRate())
                        .build());
                if (log.isInfoEnabled()) {
                  log.info(
                      "Initializing IVF-PQ parameter values: metric {}, pqBits {}, pqDim {}, nLists {}, kmeansNIters {}, kmeansTrainsetFraction {}, nProbes {}, lutDtype {}, internalDistanceDtype {}, refinementRate {}",
                      vectorType.getCuVSIvfPqIndexParamsMetric(),
                      vectorType.getCuVSIvfPqIndexParamsPqBits(),
                      vectorType.getCuVSIvfPqIndexParamsPqDim(),
                      vectorType.getCuVSIvfPqIndexParamsNLists(),
                      vectorType.getCuVSIvfPqIndexParamsKmeansNIters(),
                      vectorType.getCuVSIvfPqIndexParamsKmeansTrainsetFraction(),
                      vectorType.getCuVSIvfPqSearchParamsNProbes(),
                      vectorType.getCuVSIvfPqSearchParamsLutDtype(),
                      vectorType.getCuVSIvfPqSearchParamsInternalDistanceDtype(),
                      vectorType.getCuVSIvfPqParamsRefinementRate());
                }
              }

              if (log.isInfoEnabled()) {
                log.info(
                    "Initializing Lucene99AcceleratedHNSWVectorsFormat with parameter values: cuvsWriterThreads {}, cuvsIntGraphDegree {}, cuvsGraphDegree {}, cuvsHnswLayers {}, cuvsHnswM {}, cuvsHnswEfConstruction {}, cagraGraphBuildAlgo {}",
                    cuvsWriterThreads,
                    cuvsIntGraphDegree,
                    cuvsGraphDegree,
                    cuvsHnswLayers,
                    cuvsHnswM,
                    cuvsHnswEfConstruction,
                    buildAlgo);
              }
              return new Lucene99AcceleratedHNSWVectorsFormat(paramsBuilder.build());
            } else if (DenseVectorField.HNSW_ALGORITHM.equals(knnAlgorithm)) {
              return fallbackCodec.getKnnVectorsFormatForField(field);
            } else {
              throw new SolrException(
                  SolrException.ErrorCode.SERVER_ERROR,
                  knnAlgorithm + " KNN algorithm is not supported");
            }
          }
          return fallbackCodec.getKnnVectorsFormatForField(field);
        }
      };

  private static synchronized void loadCudaRuntime() {
    if (cudaRuntimeLoaded) {
      return;
    }

    String javaLibraryPath = System.getProperty("java.library.path", "");
    for (String libraryPath : javaLibraryPath.split(java.io.File.pathSeparator)) {
      if (libraryPath.isBlank()) {
        continue;
      }
      Path directory = Path.of(libraryPath);
      if (!Files.isDirectory(directory)) {
        continue;
      }
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "libcudart.so*")) {
        for (Path candidate : stream) {
          System.load(candidate.toAbsolutePath().toString());
          cudaRuntimeLoaded = true;
          log.info("Loaded CUDA runtime library before cuVS initialization: {}", candidate);
          return;
        }
      } catch (IOException | UnsatisfiedLinkError e) {
        log.debug("Could not load CUDA runtime from directory {}", directory, e);
      }
    }

    log.warn(
        "Could not find libcudart.so on java.library.path; cuVS initialization may fail if CUDA symbols are unresolved");
    cudaRuntimeLoaded = true;
  }
}
