package org.apache.solr.core;

import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene101.Lucene101Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsFormat;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.DenseVectorField;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

public class CuvsCodec extends FilterCodec {
    private final SolrCore core;
    private final Lucene101Codec fallbackCodec;

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private PerFieldKnnVectorsFormat perFieldKnnVectorsFormat = new PerFieldKnnVectorsFormat() {
        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String f) {
            return getKnn(f);
        }
    };
    CuVSVectorsFormat cuvsVectorsFormat;

    public CuvsCodec(SolrCore core, Lucene101Codec fallback, NamedList<?> args) {
        super("CuVSCodec",fallback);
        this.core = core;
        this.fallbackCodec = fallback;
        cuvsVectorsFormat = new CuVSVectorsFormat(Integer.parseInt(args._getStr("cuvsWriterThreads", "1")),
                Integer.parseInt(args._getStr("intGraphDegree", "1")),
                Integer.parseInt(args._getStr("graphDegree", "1")),
                CuVSVectorsWriter.IndexType.CAGRA);

        log.info("Created the CuVS Vectors Format: " + cuvsVectorsFormat);

    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return perFieldKnnVectorsFormat;
    }

    private KnnVectorsFormat getKnn(String field) {
        if(core == null) return cuvsVectorsFormat;// Added for test purposes only
        final SchemaField schemaField = core.getLatestSchema().getFieldOrNull(field);
        FieldType fieldType = (schemaField == null ? null : schemaField.getType());
        if (fieldType instanceof DenseVectorField) {
            //TODO should we have a special field type?
            DenseVectorField vectorType = (DenseVectorField) fieldType;
            String knnAlgorithm = vectorType.getKnnAlgorithm();
            log.info("The field's algo type is: " + knnAlgorithm);
            if ("cuvs".equals(knnAlgorithm)) {
                return cuvsVectorsFormat;
            } else if (DenseVectorField.HNSW_ALGORITHM.equals(knnAlgorithm)) {
                fallbackCodec.getKnnVectorsFormatForField(field);
            } else {
                throw new SolrException(
                        SolrException.ErrorCode.SERVER_ERROR, knnAlgorithm + " KNN algorithm is not supported");
            }
        }
        return fallbackCodec.getKnnVectorsFormatForField(field);

    }
}
