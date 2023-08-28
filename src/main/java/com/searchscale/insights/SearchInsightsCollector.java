package com.searchscale.insights;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.KeeperException.NoNodeException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SearchInsightsCollector 
{
	private static CommandLine getParsedCLICommands(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption("c", "zkhost", true, "ZK host (with chroot, if applicable), example: zk-host:2181 or zk-host1:2181/solr");
		options.addOption("h", "collect-host-metrics", false, "Collect Host Metrics");
		options.addOption("s", "collect-solr-metrics", false, "Collect Solr Metrics");
		options.addOption("z", "collect-zk-metrics",   false, "Collect ZK Metrics");
		options.addRequiredOption("o", "output-directory", true, "Output Directory");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse( options, args);
		return cmd;
	}

	public static void main( String[] args ) throws Exception
	{
		String zkhost = null;

		CommandLine cmd = getParsedCLICommands(args);
		zkhost = getZKHosts(cmd, zkhost);

		String outputDirectory = cmd.getOptionValue("o");
		if (!(new File(outputDirectory).exists() && new File(outputDirectory).isDirectory())) {
			if (new File(outputDirectory).mkdirs() == false) {
				throw new RuntimeException("Unable to create directory: " + outputDirectory);
			}
		}

		if (cmd.hasOption("collect-zk-metrics")) {
			CuratorFramework client = CuratorFrameworkFactory.newClient(zkhost, new RetryNTimes(3, 500));
			client.start();
			Map<String, String> zkDump = dumpZkData(client, "/");
			new File(outputDirectory + File.separatorChar + "zookeeper").mkdirs();
			FileWriter writer = new FileWriter(new File(
					outputDirectory + File.separatorChar + "zookeeper" + File.separatorChar + "zkdump.json"));
			new ObjectMapper().writeValue(writer, zkDump);
			writer.close();
			client.close();
		}

		if (cmd.hasOption("collect-solr-metrics")) {
			CuratorFramework client = CuratorFrameworkFactory.newClient(zkhost, new RetryNTimes(3, 500));
			String clusterStateOutput = null;
			client.start();
			List<String> solrHostURLs = getSolrURLs(client);

			for (String solrHost: solrHostURLs ) {
				// System.out.println("Reading metrics from " + solrHost + "...");
				String metricsOutput = fetchURL(solrHost + "/admin/metrics");
				String metricsDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "metrics";
				new File(metricsDir).mkdirs();
				FileUtils.write(
						new File((metricsDir + File.separatorChar) + (solrHost.replaceAll("/", "_"))),
						metricsOutput, Charset.forName("UTF-8"));

				// System.out.println("Reading Cluster State through " + solrHost + "...");
				clusterStateOutput = fetchURL(solrHost + "/admin/collections?action=CLUSTERSTATUS");
				String clusterStateDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "clusterstate";
				new File(clusterStateDir).mkdirs();
				FileUtils.write(
						new File(clusterStateDir + File.separatorChar + (solrHost.replaceAll("/", "_"))),
						clusterStateOutput, Charset.forName("UTF-8"));
			}

			if (clusterStateOutput != null) {
				// Use the last fetched cluster state to now fetch segments and luke info:
				Map<String, Object> cluster = new ObjectMapper().readValue(clusterStateOutput, Map.class);
				Map<String, Map> collections = ((Map<String, Map>)cluster.get("cluster")).get("collections");
				for (String collectionName: collections.keySet()) {
					Map coll = collections.get(collectionName);
					Map<String, Map> shards = (Map<String, Map>)coll.get("shards");
					for (String shardName: shards.keySet()) {
						Map<String, Map> shard = (Map<String, Map>) shards.get(shardName);
						Map<String, Map<String, String>> replicas = (Map<String, Map<String, String>>)shard.get("replicas");
						for (String coreNodeName: replicas.keySet()) {
							Map<String, String> replica = replicas.get(coreNodeName);
							boolean leader = Boolean.valueOf(replica.get("leader"));
							if (!leader) continue;
							String core = replica.get("core");
							String baseUrl = replica.get("base_url");
							// Fetch
							String segmentsOutput = fetchURL(baseUrl + "/" + core + "/admin/segments");
							String lukeOutput = fetchURL(baseUrl + "/" + core + "/admin/luke");
							// Write
							String coresInfoDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "cores";
							new File(coresInfoDir).mkdirs();
							FileUtils.write(
									new File(coresInfoDir + File.separatorChar + core+"_segments"),
									segmentsOutput, Charset.forName("UTF-8"));
							FileUtils.write(
									new File(coresInfoDir + File.separatorChar + core+"_luke"),
									lukeOutput, Charset.forName("UTF-8"));
						}
					}
				}
			}

			client.close();
		}    	
	}

	private static Map<String, String> dumpZkData(CuratorFramework client, String initialPath) throws Exception {
		Map<String, String> zkDump = new LinkedHashMap<>();
		Queue<String> queue = new ArrayDeque();
		queue.offer(initialPath);
		while (!queue.isEmpty()) {
			String path = queue.remove();
			byte dataBytes[] = client.getData().forPath(path);
			String data = dataBytes == null? null: new String(dataBytes);
			zkDump.put(path, data);
			List<String> children = new ArrayList();
			for (String child: client.getChildren().forPath(path))
				children.add((path.endsWith("/") ? path: (path + "/")) + child);
			queue.addAll(children);
		}
		return zkDump;
	}

	private static String fetchURL(String endpoint) throws IOException, MalformedURLException, ProtocolException {
		HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
		con.setRequestMethod("GET");

		String metricsOutput = IOUtils.toString(con.getInputStream(), "UTF-8");
		return metricsOutput;
	}

	private static List<String> getSolrURLs(CuratorFramework client)
			throws Exception, JsonProcessingException, JsonMappingException {
		List<String> solrHostURLs;
		String clusterPropsStr = "{}";
		try {
			clusterPropsStr = new String(client.getData().forPath("/clusterprops.json"), Charset.forName("UTF-8"));
		} catch (NoNodeException ex) {}

		Map<String, Object> clusterProps = new ObjectMapper().readValue(clusterPropsStr, Map.class);

		String urlScheme = clusterProps.containsKey("urlScheme")? clusterProps.get("urlScheme").toString(): "http";

		List<String> liveNodes = (client.getChildren().forPath("/live_nodes"));
		solrHostURLs = new ArrayList<>();
		for (String node: liveNodes) {
			solrHostURLs.add((urlScheme + "://" + node).replaceAll("_solr$", "/solr"));
		}
		return solrHostURLs;
	}

	private static String getZKHosts(CommandLine cmd, String zkhost) throws ParseException {
		if(cmd.hasOption("c")) {
			zkhost = cmd.getOptionValue("c");
			System.out.println("ZK host is: " + zkhost);
		}
		return zkhost;
	}

}
