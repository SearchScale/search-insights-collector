# search-insights
Collect raw metrics and diagnostics information from a search Cluster for insights (visualization, analytics and audits)

Building
--------

    mvn clean compile assembly:single


Running
-------

    ./search-insights-collector.sh [--collect-host-metrics | --collect-zk-metrics | --collect-solr-metrics | --zkhost <zkhost> | -d <comma separated list of Solr hosts>]

    Examples:
    
    #  ./search-insights-collector.sh --collect-host-metrics --collect-zk-metrics --collect-solr-metrics --zkhost myzookeeperhost1:2181

    #  ./search-insights-collector.sh --collect-solr-metrics -d http://solr1:8983/solr,http://solr2:8983/solr

    Notes:
    
    # --zkhost parameter is necessary if using --collect-zk-metrics
    # To supply Solr URLs directly, use -d parameter. If not specified, Solr URLs will be looked up in live_nodes of ZooKeeper (if --zkhost is specified)
    # To disable collection of logs and field metrics (Luke), you can add --disable-expensive-operations parameter

Outputs
-------

* Output will be available in archives/ directory.
