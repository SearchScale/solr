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

import com.nvidia.cuvs.lucene.Lucene99AcceleratedHNSWBinaryQuantizedVectorsFormat;
import com.nvidia.cuvs.lucene.Lucene99AcceleratedHNSWQuantizedVectorsFormat;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.commons.io.file.PathUtils;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.tests.mockfile.FilterPath;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.BinaryQuantizedDenseVectorField;
import org.apache.solr.schema.ScalarQuantizedDenseVectorField;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test to verify indexing and searching using cuVS with quantized vectors (binary and
 * scalar 7-bit).
 *
 * @since 10.0.0
 */
public class TestCuVSQuantizedVectorsIT extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static Random random;
  private static List<List<Float>> binaryDataset;
  private static List<List<Float>> scalarDataset;
  private static final int DATASET_SIZE = 100;
  private static final int DATASET_DIMENSION = 128;
  private static final int TOPK = 10;
  private static final String ID_FIELD = "id";
  private static final String BINARY_VECTOR_FIELD = "binary_vector";
  private static final String SCALAR_VECTOR_FIELD = "scalar_vector";
  private static final String SOLRCONFIG_XML = "solrconfig.xml";
  private static final String SCHEMA_XML = "schema-quantized-cuvs.xml";
  private static final String COLLECTION = "collection1";
  private static final String CONF_DIR = COLLECTION + "/conf";

  @BeforeClass
  public static void beforeClass() throws Exception {
    // Check if cuVS is supported
    assumeTrue(
        "Skipping cuVS quantized tests - cuVS not supported",
        Lucene99AcceleratedHNSWBinaryQuantizedVectorsFormat.supported()
            || Lucene99AcceleratedHNSWQuantizedVectorsFormat.supported());

    Path tmpSolrHome = createTempDir();
    Path tmpConfDir = FilterPath.unwrap(tmpSolrHome.resolve(CONF_DIR));
    Path testHomeConfDir = TEST_HOME().resolve(CONF_DIR);
    Files.createDirectories(tmpConfDir);

    // Copy solrconfig.xml if it exists, otherwise create a minimal one
    Path solrconfigPath = testHomeConfDir.resolve(SOLRCONFIG_XML);
    if (Files.exists(solrconfigPath)) {
      PathUtils.copyFileToDirectory(solrconfigPath, tmpConfDir);
    } else {
      // Create minimal solrconfig with cuVS codec
      String solrconfigContent =
          "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
              + "<config>\n"
              + "  <luceneMatchVersion>10.0.0</luceneMatchVersion>\n"
              + "  <codecFactory class=\"org.apache.solr.cuvs.CuVSCodecFactory\"/>\n"
              + "</config>";
      Files.write(tmpConfDir.resolve(SOLRCONFIG_XML), solrconfigContent.getBytes());
    }

    // Create schema with quantized vector fields
    createQuantizedSchema(tmpConfDir.resolve(SCHEMA_XML));

    initCore(SOLRCONFIG_XML, SCHEMA_XML, tmpSolrHome);
    random = new Random(42);
    binaryDataset = generateRandomVectors(random, DATASET_SIZE, DATASET_DIMENSION);
    scalarDataset = generateRandomVectors(random, DATASET_SIZE, DATASET_DIMENSION);
  }

  private static void createQuantizedSchema(Path schemaPath) throws IOException {
    String schemaContent =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
            + "<schema name=\"schema-quantized-cuvs\" version=\"1.0\">\n"
            + "  <fieldType name=\"string\" class=\"solr.StrField\" multiValued=\"false\"/>\n"
            + "  <fieldType name=\"plong\" class=\"solr.LongPointField\" useDocValuesAsStored=\"false\"/>\n"
            + "\n"
            + "  <!-- Binary Quantized Vector Field with cuVS -->\n"
            + "  <fieldType name=\"binary_quantized_vector\" class=\"solr.BinaryQuantizedDenseVectorField\"\n"
            + "             vectorDimension=\""
            + DATASET_DIMENSION
            + "\" knnAlgorithm=\"cagra_hnsw\"/>\n"
            + "\n"
            + "  <!-- Scalar Quantized Vector Field (7-bit) with cuVS -->\n"
            + "  <fieldType name=\"scalar_quantized_vector\" class=\"solr.ScalarQuantizedDenseVectorField\"\n"
            + "             vectorDimension=\""
            + DATASET_DIMENSION
            + "\" bits=\"7\" knnAlgorithm=\"cagra_hnsw\"\n"
            + "             similarityFunction=\"cosine\"/>\n"
            + "\n"
            + "  <field name=\"id\" type=\"string\" indexed=\"true\" stored=\"true\" required=\"true\" multiValued=\"false\"/>\n"
            + "  <field name=\""
            + BINARY_VECTOR_FIELD
            + "\" type=\"binary_quantized_vector\" indexed=\"true\" stored=\"true\"/>\n"
            + "  <field name=\""
            + SCALAR_VECTOR_FIELD
            + "\" type=\"scalar_quantized_vector\" indexed=\"true\" stored=\"true\"/>\n"
            + "\n"
            + "  <uniqueKey>id</uniqueKey>\n"
            + "</schema>";
    Files.write(schemaPath, schemaContent.getBytes());
  }

  @Test
  public void testBinaryQuantizedIndexingAndSearch() throws IOException {
    assumeTrue(
        "Skipping binary quantized test - cuVS not supported",
        Lucene99AcceleratedHNSWBinaryQuantizedVectorsFormat.supported());

    // Clear any existing documents from previous tests
    assertU(delQ("*:*"));
    assertU(commit());

    SolrCore solrCore = h.getCore();
    SolrConfig config = solrCore.getSolrConfig();
    String codecFactory = config.get("codecFactory").attr("class");
    assertEquals(
        "Unexpected solrconfig codec factory",
        "org.apache.solr.cuvs.CuVSCodecFactory",
        codecFactory);

    // Verify field type
    BinaryQuantizedDenseVectorField fieldType =
        (BinaryQuantizedDenseVectorField)
            solrCore.getLatestSchema().getField(BINARY_VECTOR_FIELD).getType();
    assertEquals("cagra_hnsw", fieldType.getKnnAlgorithm());

    // Index documents with binary quantized vectors
    log.info("Indexing {} documents with binary quantized vectors", DATASET_SIZE);
    for (int i = 0; i < DATASET_SIZE; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField(ID_FIELD, String.valueOf(i));
      doc.addField(BINARY_VECTOR_FIELD, binaryDataset.get(i));
      assertU(adoc(doc));
    }
    assertU(commit());

    // Verify indexing succeeded
    RefCounted<SolrIndexSearcher> refCountedSearcher = solrCore.getSearcher();
    IndexSearcher searcher = refCountedSearcher.get();
    assertEquals(
        "Indexed document count mismatch", DATASET_SIZE, searcher.getIndexReader().numDocs());

    float[] queryVector = getQueryVector(random, DATASET_DIMENSION);
    KnnFloatVectorQuery query = new KnnFloatVectorQuery(BINARY_VECTOR_FIELD, queryVector, TOPK);
    TopDocs results = searcher.search(query, TOPK);

    log.info("Binary quantized search returned {} results", results.totalHits);
    assertTrue("No search results returned", results.scoreDocs.length > 0);

    // Verify results have scores
    for (ScoreDoc sd : results.scoreDocs) {
      assertTrue("Score should be non-negative", sd.score >= 0);
      Document doc = searcher.storedFields().document(sd.doc);
      assertNotNull("Document should have id", doc.get(ID_FIELD));
      log.debug("Result: doc={}, id={}, score={}", sd.doc, doc.get(ID_FIELD), sd.score);
    }

    refCountedSearcher.decref();
  }

  @Test
  public void testScalarQuantizedIndexingAndSearch() throws IOException {
    assumeTrue(
        "Skipping scalar quantized test - cuVS not supported",
        Lucene99AcceleratedHNSWQuantizedVectorsFormat.supported());

    // Clear any existing documents from previous tests
    assertU(delQ("*:*"));
    assertU(commit());

    SolrCore solrCore = h.getCore();
    SolrConfig config = solrCore.getSolrConfig();
    String codecFactory = config.get("codecFactory").attr("class");
    assertEquals(
        "Unexpected solrconfig codec factory",
        "org.apache.solr.cuvs.CuVSCodecFactory",
        codecFactory);

    // Verify field type
    ScalarQuantizedDenseVectorField fieldType =
        (ScalarQuantizedDenseVectorField)
            solrCore.getLatestSchema().getField(SCALAR_VECTOR_FIELD).getType();
    assertEquals("cagra_hnsw", fieldType.getKnnAlgorithm());
    assertEquals(7, fieldType.getBits());

    // Index documents with scalar quantized vectors
    log.info("Indexing {} documents with scalar quantized vectors (7-bit)", DATASET_SIZE);
    for (int i = 0; i < DATASET_SIZE; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField(ID_FIELD, String.valueOf(i + DATASET_SIZE)); // Use different IDs
      doc.addField(SCALAR_VECTOR_FIELD, scalarDataset.get(i));
      assertU(adoc(doc));
    }
    assertU(commit());

    // Verify indexing succeeded
    RefCounted<SolrIndexSearcher> refCountedSearcher = solrCore.getSearcher();
    IndexSearcher searcher = refCountedSearcher.get();
    assertEquals(
        "Indexed document count mismatch", DATASET_SIZE, searcher.getIndexReader().numDocs());

    float[] queryVector = getQueryVector(random, DATASET_DIMENSION);
    KnnFloatVectorQuery query = new KnnFloatVectorQuery(SCALAR_VECTOR_FIELD, queryVector, TOPK);
    TopDocs results = searcher.search(query, TOPK);

    log.info("Scalar quantized search returned {} results", results.totalHits);
    assertTrue("No search results returned", results.scoreDocs.length > 0);

    // Verify results have scores
    for (ScoreDoc sd : results.scoreDocs) {
      assertTrue("Score should be non-negative", sd.score >= 0);
      Document doc = searcher.storedFields().document(sd.doc);
      assertNotNull("Document should have id", doc.get(ID_FIELD));
      log.debug("Result: doc={}, id={}, score={}", sd.doc, doc.get(ID_FIELD), sd.score);
    }

    refCountedSearcher.decref();
  }

  @Test
  public void testQuantizedVectorCodecSelection() throws Exception {
    SolrCore solrCore = h.getCore();
    CuVSCodec codec = (CuVSCodec) solrCore.getCodec();

    // Verify binary quantized field uses cuVS format
    if (Lucene99AcceleratedHNSWBinaryQuantizedVectorsFormat.supported()) {
      org.apache.lucene.codecs.KnnVectorsFormat knnFormat = codec.knnVectorsFormat();
      assertTrue(
          "Codec should use PerFieldKnnVectorsFormat",
          knnFormat instanceof PerFieldKnnVectorsFormat);
      PerFieldKnnVectorsFormat perFieldFormat = (PerFieldKnnVectorsFormat) knnFormat;
      org.apache.lucene.codecs.KnnVectorsFormat binaryFormat =
          perFieldFormat.getKnnVectorsFormatForField(BINARY_VECTOR_FIELD);
      assertNotNull("Binary quantized format should not be null", binaryFormat);
      log.info("Binary quantized format: {}", binaryFormat.getClass().getName());
      assertTrue(
          "Binary quantized field should use cuVS format",
          binaryFormat
              .getClass()
              .getName()
              .contains("AcceleratedHNSWBinaryQuantizedVectorsFormat"));
    }

    // Verify scalar quantized field uses cuVS format
    if (Lucene99AcceleratedHNSWQuantizedVectorsFormat.supported()) {
      org.apache.lucene.codecs.KnnVectorsFormat knnFormat = codec.knnVectorsFormat();
      assertTrue(
          "Codec should use PerFieldKnnVectorsFormat",
          knnFormat instanceof PerFieldKnnVectorsFormat);
      PerFieldKnnVectorsFormat perFieldFormat = (PerFieldKnnVectorsFormat) knnFormat;
      org.apache.lucene.codecs.KnnVectorsFormat scalarFormat =
          perFieldFormat.getKnnVectorsFormatForField(SCALAR_VECTOR_FIELD);
      assertNotNull("Scalar quantized format should not be null", scalarFormat);
      log.info("Scalar quantized format: {}", scalarFormat.getClass().getName());
      assertTrue(
          "Scalar quantized field should use cuVS format",
          scalarFormat.getClass().getName().contains("AcceleratedHNSWQuantizedVectorsFormat"));
    }
  }

  private static List<List<Float>> generateRandomVectors(Random random, int size, int dimensions) {
    List<List<Float>> dataset = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      List<Float> row = new ArrayList<>();
      for (int j = 0; j < dimensions; j++) {
        row.add(random.nextFloat() * 2.0f - 1.0f); // Range [-1, 1]
      }
      dataset.add(row);
    }
    return dataset;
  }

  private static float[] getQueryVector(Random random, int dimension) {
    List<Float> ql = generateRandomVectors(random, 1, dimension).get(0);
    float[] query = new float[dimension];
    for (int i = 0; i < dimension; i++) {
      query[i] = ql.get(i);
    }
    return query;
  }
}
