# search-insights
Collect raw metrics and diagnostics information from a search Cluster for insights (visualization, analytics and audits)

Building
--------

    mvn clean compile assembly:single


Running
-------

    ./search-insights-collector.sh [--collect-host-metrics | --collect-zk-metrics | --collect-solr-metrics | --zkhost <zkhost>]

    Example:  ./search-insights-collector.sh --collect-host-metrics --collect-zk-metrics --collect-solr-metrics --zkhost zk1:2181

    Note: --zkhost parameter is necessary if using --collect-solr-metrics or --collect-zk-metrics

Outputs
-------

* Output will be available in archives/ directory.
