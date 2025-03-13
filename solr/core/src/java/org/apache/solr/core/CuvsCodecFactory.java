package org.apache.solr.core;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene101.Lucene101Codec;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CodecFactory;
import org.apache.solr.core.CuvsCodec;
import org.apache.solr.core.SchemaCodecFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.util.plugin.SolrCoreAware;

public class CuvsCodecFactory extends CodecFactory implements SolrCoreAware {

    private final SchemaCodecFactory fallback;
    private SolrCore core;
    NamedList<?> args;
    Lucene101Codec fallbackCodec;
    CuvsCodec codec;
    public CuvsCodecFactory() {
        this.fallback =  new SchemaCodecFactory();;
    }

    @Override
    public Codec getCodec() {
        if(codec == null) {
            codec = new CuvsCodec(core, fallbackCodec,args);
        }
        return codec;
    }

    @Override
    public void inform(SolrCore solrCore) {
        this.core = solrCore;
        fallback.inform(solrCore);
    }

    @Override
    public void init(NamedList<?> args) {
        fallback.init(args);
        this.args = args;
        fallbackCodec = (Lucene101Codec) fallback.getCodec();
    }

}
