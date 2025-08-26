package org.apache.solr.search.neural;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;

public class CuvsQParserPlugin extends QParserPlugin {
  public static final String NAME = "cuvs";

  @Override
  public QParser createParser(
      String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new CuvsQParser(qstr, localParams, params, req);
  }
}
