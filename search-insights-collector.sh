################### OPTIONS PARSING #####################
VALID_ARGS=$(getopt -o ehszcd: --long disable-expensive-operations,collect-host-metrics,collect-solr-metrics,collect-zk-metrics,zkhost: -- "$@")
if [[ $? -ne 0 ]]; then
    exit 1;
fi

JAVA_OPTS=""

eval set -- "$VALID_ARGS"
while [ : ]; do
  case "$1" in
    -e | --disable-expensive-operations)
        DISABLE_EXPENSIVE="True"
        JAVA_OPTS="$JAVA_OPTS --disable-expensive-operations"
        shift
        ;;
    -h | --collect-host-metrics)
        HOST_METRICS="True"
        JAVA_OPTS="$JAVA_OPTS --collect-host-metrics"
        shift
        ;;
    -s | --collect-solr-metrics)
        JAVA_OPTS="$JAVA_OPTS --collect-solr-metrics"
        shift
        ;;
    -z | --collect-zk-metrics)
        JAVA_OPTS="$JAVA_OPTS --collect-zk-metrics"
        shift
        ;;
    -c | --zkhost)
        ZKHOST=$2
        JAVA_OPTS="$JAVA_OPTS -c $ZKHOST"
        shift 2
        ;;
    -d )
        SOLRURLS=$2
        JAVA_OPTS="$JAVA_OPTS -d $SOLRURLS"
        shift 2
        ;;
    --) shift; 
        break 
        ;;
  esac
done

############ INIT ###############
TIMESTAMP=`date +"%Y_%m_%d-%H_%M_%S"`_$RANDOM
OUTDIR=searchinsights-$TIMESTAMP
mkdir -p $OUTDIR

################ COMPUTE THE HOST METRICS #######################
if [[ "True" == "$HOST_METRICS" ]];
then
	mkdir -p $OUTDIR/host

	c=0
	while IFS= read -r line
	do
	  c=$((c+1))
	  if ! (( $c % 2 )) ; then
	    key=$prevLine
	    cmd=$line
	    $cmd >> $OUTDIR/host/$key.txt
	    #echo "Written $OUTDIR/host/$key.txt file"
	  fi
	  prevLine=$line
	done < <(jq -r ".|to_entries[][]" host-metrics.json)
  echo "Done collecting host metrics in $OUTDIR/host directory"
fi

######### COMPUTE THE SOLR and ZOOKEEPER METRICS ############
java -cp search-insights-collector-0.8-jar-with-dependencies.jar:target/search-insights-collector-0.8-jar-with-dependencies.jar:. \
         com.searchscale.insights.SearchInsightsCollector --output-directory $OUTDIR $JAVA_OPTS

############ PREPARE THE TARBALL ###############
filename="collector-$TIMESTAMP.tar"
mkdir -p archives
tar -cf archives/$filename $OUTDIR
gzip archives/$filename
rm -rf $OUTDIR
echo "Collected insights into file: archives/$filename.gz"
