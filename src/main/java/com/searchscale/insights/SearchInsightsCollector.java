package com.searchscale.insights;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
		options.addOption("d", "direct-solr-urls", true, "Direct Solr URLs (comma separated), example: http://solr2:8983/solr,http://solr1:8983/solr");
		options.addOption("h", "collect-host-metrics", false, "Collect Host Metrics");
		options.addOption("s", "collect-solr-metrics", false, "Collect Solr Metrics");
		options.addOption("z", "collect-zk-metrics",   false, "Collect ZK Metrics");
		options.addOption("e", "disable-expensive-operations",   false, "Don't collect Luke, logs etc.");
		options.addOption("n", "cluster-name",   true, "Name of the cluster (no spaces)");
		options.addRequiredOption("o", "output-directory", true, "Output Directory");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse( options, args);
		return cmd;
	}

	public static void main( String[] args ) throws Exception
	{
		
		
		CommandLine cmd = getParsedCLICommands(args);
		String zkhost = getZKHost(cmd);
		String directSolrUrls[] = getDirectSolrURLs(cmd);
		boolean disableExpensiveOps = cmd.hasOption("disable-expensive-operations") ? true: false;
		if (disableExpensiveOps) System.out.println("Expensive operations are disabled!");

		String outputDirectory = cmd.getOptionValue("o");
		if (!(new File(outputDirectory).exists() && new File(outputDirectory).isDirectory())) {
			if (new File(outputDirectory).mkdirs() == false) {
				throw new RuntimeException("Unable to create directory: " + outputDirectory);
			}
		}

		String collectorVersion = (SearchInsightsCollector.class.getPackage().getImplementationVersion());
		FileUtils.write(new File(outputDirectory + File.separatorChar + "collector.properties"), "collector-version=" + collectorVersion + "\n", Charset.forName("UTF-8"));
		if (cmd.hasOption("collect-zk-metrics")) {
			if (zkhost == null || zkhost.isBlank()) throw new RuntimeException("--collect-zk-metrics was specified but ZK host (-c / --zkhost) not specified.");
			System.out.println("Started collecting ZK metrics...");
			CuratorFramework client = CuratorFrameworkFactory.newClient(zkhost, new RetryNTimes(3, 500));
			client.start();
			Map<String, String> zkDump = dumpZkData(client, "/");
			new File(outputDirectory + File.separatorChar + "zookeeper").mkdirs();
			FileWriter writer = new FileWriter(new File(
					outputDirectory + File.separatorChar + "zookeeper" + File.separatorChar + "zkdump.json"));
			new ObjectMapper().writeValue(writer, zkDump);
			writer.close();
			client.close();
			System.out.println("Done ZK metrics!");
		}

		if (cmd.hasOption("collect-solr-metrics")) {
			System.out.println("Started collecting Solr metrics...");
			List<String> solrHostURLs = null;

			if (directSolrUrls != null) {
				solrHostURLs = Arrays.asList(directSolrUrls);
			} else {
				CuratorFramework client = CuratorFrameworkFactory.newClient(zkhost, new RetryNTimes(3, 500));
				client.start();
				solrHostURLs = getSolrURLs(client);
				client.close();
			}

			System.out.println("Solr URLs are: " + solrHostURLs);
			for (String solrHost: solrHostURLs ) {
				if (solrHost.endsWith("/solr") == false) {
					solrHost += solrHost.endsWith("/")? "solr": "/solr";
				}

				collectSolrNodeLevelEndpoint("metrics", solrHost + "/admin/metrics", solrHost, outputDirectory);
				collectSolrNodeLevelEndpoint("threads", solrHost + "/admin/info/threads", solrHost, outputDirectory);
				if (!disableExpensiveOps) {
					collectSolrNodeLevelEndpoint("logs", solrHost + "/admin/info/logging?since=" + LocalDateTime.now().minusDays(1).toEpochSecond(ZoneOffset.UTC), solrHost, outputDirectory);
				}
				collectSolrNodeLevelEndpoint("clusterstate", solrHost + "/admin/collections?action=CLUSTERSTATUS", solrHost, outputDirectory);
				collectSolrNodeLevelEndpoint("overseer", solrHost + "/admin/collections?action=OVERSEERSTATUS", solrHost, outputDirectory);
				String coresOutput = collectSolrNodeLevelEndpoint("cores", solrHost + "/admin/cores", solrHost, outputDirectory);

				for (String core: ((Map<String, Object>)new ObjectMapper().readValue(coresOutput, Map.class).get("status")).keySet()) {
					String coresInfoDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "cores";
					new File(coresInfoDir).mkdirs();

					System.out.println("\tFor core " + core);
					for (String adminEndpoint: new String[] {"segments", disableExpensiveOps? null: "luke", "plugins"}) {
						if (adminEndpoint==null) continue;
						System.out.println("\t\tReading " + adminEndpoint + "...");
						String output = fetchURL(solrHost + "/" + core + "/admin/" + adminEndpoint);
						FileUtils.write(new File(coresInfoDir + File.separatorChar + core + "_" + adminEndpoint), output, Charset.forName("UTF-8"));
					}
				}
			}
		}    	
	}

	private static String collectSolrNodeLevelEndpoint(String item, String endpoint, String solrHost, String outputDirectory) throws MalformedURLException, ProtocolException, IOException {
		System.out.println("Reading " + item + " from " + solrHost + "...");
		String output = fetchURL(endpoint);
		String dir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + item;
		new File(dir).mkdirs();
		FileUtils.write(
				new File((dir + File.separatorChar) + (solrHost.replaceAll("/", "_"))),
				output, Charset.forName("UTF-8"));
		return output;
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
		endpoint += endpoint.contains("?")? "&_=searchscale": "?_=searchscale";
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

	private static String getZKHost(CommandLine cmd) throws ParseException {
		String zkhost = null;
		if(cmd.hasOption("c")) {
			zkhost = cmd.getOptionValue("c");
			System.out.println("ZK host is: " + zkhost);
		}
		return zkhost;
	}

	private static String[] getDirectSolrURLs(CommandLine cmd) throws ParseException {
		String urls[] = null;
		if(cmd.hasOption("d")) {
			String arg = cmd.getOptionValue("d");
			urls = arg.split(",");
		}
		return urls;
	}

}
