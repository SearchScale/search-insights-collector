# search-insights
Collect raw metrics and diagnostics information from a search Cluster for insights (visualization, analytics and audits)

Building
--------

    mvn clean compile assembly:single


Running
-------

Syntax:

    ./search-insights-collector.sh [--collect-host-metrics | --collect-zk-metrics | --collect-solr-metrics | --zkhost <zkhost> | -d <comma separated list of Solr hosts> | -n <clustername>]

Examples:
    
*  `./search-insights-collector.sh --collect-host-metrics --collect-zk-metrics --collect-solr-metrics --zkhost myzookeeperhost1:2181`
*  `./search-insights-collector.sh --collect-solr-metrics -d http://solr1:8983/solr,http://solr2:8983/solr`

Notes:
    
* `--zkhost` parameter is necessary if using `--collect-zk-metrics`
* To supply Solr URLs directly, use `-d` parameter. If not specified, Solr URLs will be looked up in live_nodes of ZooKeeper (if `--zkhost` is specified)
* To disable collection of logs and field metrics (Luke), you can add `--disable-expensive-operations` parameter
* If you have multiple Solr clusters, you can specify `-n <clustername>` parameter to ensure the generated file contains the cluster name in prefix. The name shouldn't contain spaces or special characters (other than '-').
* If you have additional metadata, e.g. datacenter name, availability zone etc., pass them using `-k datacenter=dc1,az=abc`.

Authenticated Solr Clusters:

If your Solr cluster is protected with Basic Authentication, then export the username and password to access the Solr cluster to the following environemnt variables before executing the collector: INSIGHTS_COLLECTOR_USERNAME and INSIGHTS_COLLECTOR_PASSWORD.

Example:

    export INSIGHTS_COLLECTOR_USERNAME=hello
    export INSIGHTS_COLLECTOR_PASSWORD=world

Outputs
-------

* Output will be available in archives/ directory.
