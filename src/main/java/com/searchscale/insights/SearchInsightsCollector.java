package com.searchscale.insights;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

		// Individual operations can be disabled
		options.addOption("g", "disable-segments", false, "Don't collect segments");
		options.addOption("t", "disable-threads", false, "Don't collect threads");
		options.addOption("p", "disable-plugins", false, "Don't collect plugins");
		options.addOption("o", "disable-overseer", false, "Don't collect overseer info");
		options.addOption("k", "disable-luke", false, "Don't collect luke");
		options.addOption("l", "disable-logs", false, "Don't collect logs");

		options.addRequiredOption("n", "cluster-name",   true, "Name of the cluster (no spaces)");
		options.addOption("k", "keys",   true, "Additional metadata keys");
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

		boolean disableSegments = cmd.hasOption("disable-segments") ? true: false;
		boolean disableThreads = cmd.hasOption("disable-threads") ? true: false;
		boolean disablePlugins = cmd.hasOption("disable-plugins") ? true: false;
		boolean disableOverseer = cmd.hasOption("disable-overseer") ? true: false;
		boolean disableLuke = cmd.hasOption("disable-luke") ? true: false;
		boolean disableLogs = cmd.hasOption("disable-logs") ? true: false;

		
		String outputDirectory = cmd.getOptionValue("o");
		if (!(new File(outputDirectory).exists() && new File(outputDirectory).isDirectory())) {
			if (new File(outputDirectory).mkdirs() == false) {
				throw new RuntimeException("Unable to create directory: " + outputDirectory);
			}
		}

		String collectorVersion = (SearchInsightsCollector.class.getPackage().getImplementationVersion());
		FileUtils.write(new File(outputDirectory + File.separatorChar + "collector.properties"), 
				"collector-version=" + collectorVersion + "\n" 
				+ "timestamp=" + Instant.now().toEpochMilli() + "\n" 
		        + "cluster-name=" + (cmd.hasOption("n") ? cmd.getOptionValue("n"): "") + "\n"
		        + (cmd.hasOption("k") ? String.join("\n", Arrays.asList(cmd.getOptionValue("k").split(","))): "") + "\n",
				Charset.forName("UTF-8"));
		if (cmd.hasOption("collect-zk-metrics")) {
			if (zkhost == null || zkhost.isEmpty()) throw new RuntimeException("--collect-zk-metrics was specified but ZK host (-c / --zkhost) not specified.");
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
				if(!disableThreads) collectSolrNodeLevelEndpoint("threads", solrHost + "/admin/info/threads", solrHost, outputDirectory);
				if (!disableExpensiveOps) {
					if(!disableLogs) collectSolrNodeLevelEndpoint("logs", solrHost + "/admin/info/logging?since=" + LocalDateTime.now().minusDays(1).toEpochSecond(ZoneOffset.UTC), solrHost, outputDirectory);
				}
				collectSolrNodeLevelEndpoint("clusterstate", solrHost + "/admin/collections?action=CLUSTERSTATUS", solrHost, outputDirectory);
				if(!disableOverseer) collectSolrNodeLevelEndpoint("overseer", solrHost + "/admin/collections?action=OVERSEERSTATUS", solrHost, outputDirectory);
				String coresOutput = collectSolrNodeLevelEndpoint("cores", solrHost + "/admin/cores", solrHost, outputDirectory);

				try {
					for (String core: ((Map<String, Object>)new ObjectMapper().readValue(coresOutput, Map.class).get("status")).keySet()) {
						String coresInfoDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "cores";
						new File(coresInfoDir).mkdirs();

						System.out.println("\tFor core " + core);
						for (String adminEndpoint: new String[] {
								disableSegments? null: "segments", 
								(disableExpensiveOps || disableLuke)? null: "luke", 
								disablePlugins? null: "plugins"}) {
							if (adminEndpoint==null) continue;
							System.out.println("\t\tReading " + adminEndpoint + "...");
							String output = fetchURL(solrHost + "/" + core + "/admin/" + adminEndpoint);
							FileUtils.write(new File(coresInfoDir + File.separatorChar + core + "_" + adminEndpoint), output, Charset.forName("UTF-8"));
						}
					}
				} catch (Exception ex) {
					ex.printStackTrace();
					String errorsDir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + "errors";
					new File(errorsDir).mkdirs();
					FileUtils.writeStringToFile(new File(errorsDir + File.separatorChar + Instant.now().toEpochMilli() + ".txt"), new ObjectMapper().writeValueAsString(ex), Charset.forName("UTF-8"));
				}
			}
		}    	
	}

	private static String collectSolrNodeLevelEndpoint(String item, String endpoint, String solrHost, String outputDirectory) throws JsonProcessingException {
		if (System.getenv("INSIGHTS_COLLECTOR_USERNAME") != null && System.getenv("INSIGHTS_COLLECTOR_PASSWORD") != null) {
			System.out.println("Trying to use basic authentication to connect to the cluster: "
					+ "user=" + System.getenv("INSIGHTS_COLLECTOR_USERNAME") +
					", pass="+"*".repeat(System.getenv("INSIGHTS_COLLECTOR_PASSWORD").length()));
		} else {
			System.out.println("Trying to connect to Solr cluster without authentication (INSIGHTS_COLLECTOR_USERNAME and/or INSIGHTS_COLLECTOR_PASSWORD not specified)");
		}
		System.out.println("Reading " + item + " from " + solrHost + "...");
		String output;
		try {
			output = fetchURL(endpoint);
		} catch (Exception e) {
			e.printStackTrace();

			// if there's a problem accessing the endpoint, write the exception in the collector output
			output = new ObjectMapper().writeValueAsString(e);
		}
		String dir = outputDirectory + File.separatorChar + "solr" + File.separatorChar + item;
		new File(dir).mkdirs();
		try {
			FileUtils.write(
					new File((dir + File.separatorChar) + (solrHost.replaceAll("/", "_"))),
					output, Charset.forName("UTF-8"));
		} catch (IOException e) {
			e.printStackTrace();
		}
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

	private static String fetchURL(String endpoint) throws URISyntaxException, IOException, InterruptedException {
		endpoint += endpoint.contains("?")? "&_=searchscale": "?_=searchscale";
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				  .uri(new URI(endpoint))
				  .GET();
		if (System.getenv("INSIGHTS_COLLECTOR_USERNAME") != null && System.getenv("INSIGHTS_COLLECTOR_PASSWORD") != null) {
			String auth = System.getenv("INSIGHTS_COLLECTOR_USERNAME") + ":" + System.getenv("INSIGHTS_COLLECTOR_PASSWORD");
			byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
			String authHeaderValue = "Basic " + new String(encodedAuth);
			requestBuilder.setHeader("Authorization", authHeaderValue);
		}
		HttpResponse<String> response = HttpClient
				  .newBuilder()
				  .build()
				  .send(requestBuilder.build(), BodyHandlers.ofString());
		if (!(response.statusCode() >= 200 && response.statusCode()<300)) {
			throw new RuntimeException("Problem accessing Solr: " + response.statusCode() + "\n" + response.body());
		}
		return response.body();
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
