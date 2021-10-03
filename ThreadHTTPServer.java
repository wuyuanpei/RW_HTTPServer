
/**
 ** A threadpool server
 **/

import java.io.*;
import java.net.*;
import java.util.*;
import org.apache.commons.cli.*;

class ThreadHTTPServer {

	public static int serverPort;

	public static String configFilePath;

	public static final String SERVER_NAME = "RW_HTTPServer/1.0";

	/* key is the server name and value is a VirtualHost object */
	public static HashMap<String, VirtualHost> virtualHosts;
	/* the default virtual host if no Host header is specified*/
	public static VirtualHost defaultVHost;

	public static HashMap<File, byte[]> cache; // cache, where key is the file and value is the content
	public static int cacheCurrentSize = 0;
	public static int cacheMaxSize = 0;

	// thread pool size (default 3)
	// based on ThreadPoolSize <number of threads> in the configuration file
	public static int threadPoolSize = 3;
	public static Thread[] serviceThreads;
	public static Vector<Socket> connSockQ;

	public static void main(String args[]) throws Exception {

		// parse argument
		parseArgument(args);

		// read config
		readConfig();
		
		// create server socket
		ServerSocket listenSocket = new ServerSocket(serverPort);

		System.out.println("server listening at: " + listenSocket);
		System.out.println("thread pool size: " + threadPoolSize);

		// create socket queue
		connSockQ = new Vector<>();

		// create thread pool
		serviceThreads = new Thread[threadPoolSize];

		// start all the threads
		for(int i = 0; i < serviceThreads.length; i++){
			serviceThreads[i] = new Thread(new ThreadHTTPRequestHandler(i));
			serviceThreads[i].start();
		}

		while (true) {

			try {

				// take a ready connection from the accepted queue
				Socket connectionSocket = listenSocket.accept();
				System.out.println("\nReceive request from " + connectionSocket);

				// put the connectionSocket to the Q
				synchronized(connSockQ) {
					connSockQ.add(connectionSocket);
					connSockQ.notify();
				}

			} catch (IOException e) {
				System.out.println("cannot handle IO for connection socket");
			}
		}
	}

	/**
	 * Parse the arguments The only argument required is config
	 * 
	 * @param args arguments
	 */
	public static void parseArgument(String args[]) {
		Options options = new Options();

		Option configO = new Option("config", true, "configuration file");
		configO.setRequired(true);
		options.addOption(configO);

		CommandLineParser parser = new DefaultParser();

		try {
			CommandLine cmd = parser.parse(options, args);
			configFilePath = cmd.getOptionValue("config");
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("HTTPServer", options);
			System.exit(1);
		}
	}

	/**
	 * Read configuration port and set up document door and server name
	 */
	public static void readConfig() {
		File configFile = new File(configFilePath);
		virtualHosts = new HashMap<>();
		try {
			BufferedReader br = new BufferedReader(new FileReader(configFile));
			String st;
			boolean parsingVH = false;
			VirtualHost vh = null;
			while ((st = br.readLine()) != null) {
				// ignore the comments
				if(st.contains("#")){
					st = st.substring(0, st.indexOf("#"));
				}
				if(st.contains("Listen") && parsingVH == false){
					serverPort = Integer.parseInt(st.substring(st.indexOf("Listen") + 7).trim());
				}
				else if(st.contains("CacheSize") && parsingVH == false){
					cacheMaxSize = 1024 * Integer.parseInt(st.substring(st.indexOf("CacheSize") + 10).trim());
					cache = new HashMap<>();
				}
				else if(st.contains("ThreadPoolSize") && parsingVH == false){
					threadPoolSize = Integer.parseInt(st.substring(st.indexOf("ThreadPoolSize") + 15).trim());
				}
				else if(st.contains("VirtualHost") && parsingVH == false){ // note that *:6789 is ignored
					vh = new VirtualHost();
					parsingVH = true;
				}
				else if(st.contains("VirtualHost") && parsingVH == true){
					virtualHosts.put(vh.getServerName(), vh);
					if(defaultVHost == null)
						defaultVHost = vh;
					vh = null;
					parsingVH = false;
				}
				else if(parsingVH == true) {
					if(st.contains("DocumentRoot")){
						int idx = st.indexOf("DocumentRoot") + 13;
						String docRoot = st.substring(idx).trim();
						// igore the quotation mark if any
						if(docRoot.charAt(0) == '\"' && docRoot.charAt(docRoot.length() - 1) == '\"')
							docRoot = docRoot.substring(1, docRoot.length() - 1);
						vh.setDocRoot(docRoot);
					} else if(st.contains("ServerName")){
						int idx = st.indexOf("ServerName") + 11;
						vh.setServerName(st.substring(idx).trim());
					}
				}
			}
		} catch (FileNotFoundException e) {
			Util.panic(2, "configuration file does not exist!");
		} catch (IOException e) {
			Util.panic(3, "cannot read configuation file!");
		} catch (NumberFormatException e){
			Util.panic(4, "cannot parse listen port!");
		}
		Util.DEBUG(virtualHosts.toString());
	}

}