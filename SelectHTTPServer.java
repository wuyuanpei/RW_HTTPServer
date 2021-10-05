
/**
 ** A per-thread, sequential processing handler server
 **/

import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.nio.channels.*;
import org.apache.commons.cli.*;

class SelectHTTPServer {

	public static int serverPort;

	public static String configFilePath;

	public static final String SERVER_NAME = "RW_HTTPServer/1.0";

	/* key is the server name and value is a VirtualHost object */
	public static HashMap<String, VirtualHost> virtualHosts;
	/* the default virtual host if no Host header is specified */
	public static VirtualHost defaultVHost;

	public static HashMap<File, byte[]> cache; // cache, where key is the file and value is the content
	public static int cacheCurrentSize = 0;
	public static int cacheMaxSize = 0;

	// maximum cpu usage (for returning 503 or 200 in heartbeating monitor)
	public static final double MAX_CPU_USAGE = 0.8;

	// maximum time (in millisec) for a connection to finish (otherwise 
	// connection over TIME_MAXIMUM will be killed [in TIME_BUFFER millisec])
	public static final long TIME_MAXIMUM = 3000;
	public static final long TIME_BUFFER = 1000;

	public static Selector selector; // the selector for the server

	public static boolean stop = false; // whether to check there is still open channels (set by ShutdownCommand)

	public static void main(String args[]) throws Exception {

		// parse argument
		parseArgument(args);

		// read config
		readConfig();

		// create server socket channel
		ServerSocketChannel sch = openServerSocketChannel(serverPort);

		System.out.println("server listening at port: " + serverPort);

		// start command thread
		CommandThread ct = new CommandThread();
		new Thread(ct).start();

		try {
			// create selector
			selector = Selector.open();

			// register server socket (no need to use attachment)
			sch.register(selector, SelectionKey.OP_ACCEPT);

		} catch (IOException e) {
			Util.panic(1, "Cannot open selector!");
		}

		// event loop
		while (true) {

			/*
			 * try {
			 * 
			 * // take a ready connection from the accepted queue Socket connectionSocket =
			 * listenSocket.accept(); System.out.println("\nReceive request from " +
			 * connectionSocket);
			 * 
			 * // process a request Thread thread = new Thread(new
			 * SelectHTTPRequestHandler(connectionSocket));
			 * 
			 * thread.start();
			 * 
			 * } catch (IOException e) {
			 * System.out.println("cannot handle IO for connection socket"); }
			 */

			try {
				// block to wait for events
				// block for at most TIME_BUFFER millisec, 
				// so that connection over TIME_MAXIMUM will be killed in TIME_BUFFER
				selector.select(TIME_BUFFER);
			} catch (IOException e) {
				e.printStackTrace();
				Util.panic(2, "Selector IOException generated!");
			} catch (ClosedSelectorException e) {
				e.printStackTrace();
				Util.panic(3, "ClosedSelectorException generated!");
			}

			// readyKeys is a set of ready events
			Set<SelectionKey> readyKeys = selector.selectedKeys();

			// create an iterator for the set
			Iterator<SelectionKey> iterator = readyKeys.iterator();

			// iterate over all events
			while (iterator.hasNext()) {
				SelectionKey key = iterator.next();
				iterator.remove();

				try {
					if (key.isAcceptable()) {
						// a new connection is ready to be accepted
						handleAccept(key);
					} // end of isAcceptable

					if (key.isReadable()) {
						handleRead(key);
					} // end of isReadable

					if (key.isWritable()) {
						handleWrite(key);
					} // end of if isWritable

				} catch (IOException e) {

					if (key != null) {
						key.cancel();
						if (key.channel() != null)
							try {
								key.channel().close();
							} catch (IOException closeex) {
							}
					}

				}
			}

			// deal with command queue
			synchronized (ct.commandQ) {
				while (!ct.commandQ.isEmpty()) {
					Command cm = ct.commandQ.remove(0);
					cm.runCommand();
				}
			}

			Iterator<SelectionKey> iter = SelectHTTPServer.selector.keys().iterator();
			long currentTime = System.currentTimeMillis();
			while (iter.hasNext()) {
				SelectionKey key = iter.next();
				SelectHTTPRequestHandler handler = (SelectHTTPRequestHandler) key.attachment();
				if(handler != null){
					handler.testAndKill(currentTime, key);
				}
			}

			if (stop) {
				// check whether there are still (not closed) keys, if not, close selector and
				// exit the event loop
				Iterator<SelectionKey> it = SelectHTTPServer.selector.keys().iterator();
				boolean allClosed = true;
				while (it.hasNext()) {
					SelectionKey key = it.next();
					if (key.isValid()) {
						allClosed = false;
					}
				}
				if (allClosed) {
					selector.close();
					System.out.println("Server shut down!");
					return;
				}
			}

		} // end of event loop
	}

	public static void handleAccept(SelectionKey key) throws IOException {

		ServerSocketChannel server = (ServerSocketChannel) key.channel();

		// extract the ready connection
		SocketChannel client = server.accept();
		Util.DEBUG("handleAccept: Accepted connection from " + client);

		// configure the connection to be non-blocking
		client.configureBlocking(false);

		// register the new connection with interests
		SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);

		// save handler
		clientKey.attach(new SelectHTTPRequestHandler());

	}

	public static void handleRead(SelectionKey key) throws IOException {

		// a connection is ready to be read
		Util.DEBUG("[>]handleRead");

		SelectHTTPRequestHandler handler = (SelectHTTPRequestHandler) key.attachment();
		handler.handleRead(key);

		Util.DEBUG("[-]handleRead");

	}

	public static void handleWrite(SelectionKey key) throws IOException {

		// a connection is ready to be written
		Util.DEBUG("[>]handleWrite");

		SelectHTTPRequestHandler handler = (SelectHTTPRequestHandler) key.attachment();
		handler.handleWrite(key);

		Util.DEBUG("[-]handleWrite");

	}

	/**
	 * set up the server socket channel
	 * 
	 * @param port
	 * @return server socket channel
	 */
	public static ServerSocketChannel openServerSocketChannel(int port) {
		ServerSocketChannel serverChannel = null;

		try {
			// open server socket for accept
			serverChannel = ServerSocketChannel.open();

			// extract server socket of the server channel and bind the port
			ServerSocket ss = serverChannel.socket();
			InetSocketAddress address = new InetSocketAddress(port);
			ss.bind(address);

			// configure it to be non blocking
			serverChannel.configureBlocking(false);

		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		} // end of catch

		return serverChannel;
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
			formatter.printHelp("SelectHTTPServer", options);
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
				if (st.contains("#")) {
					st = st.substring(0, st.indexOf("#"));
				}
				if (st.contains("Listen") && parsingVH == false) {
					serverPort = Integer.parseInt(st.substring(st.indexOf("Listen") + 7).trim());
				} else if (st.contains("CacheSize") && parsingVH == false) {
					cacheMaxSize = 1024 * Integer.parseInt(st.substring(st.indexOf("CacheSize") + 10).trim());
					cache = new HashMap<>();
				} else if (st.contains("VirtualHost") && parsingVH == false) { // note that *:6789 is ignored
					vh = new VirtualHost();
					parsingVH = true;
				} else if (st.contains("VirtualHost") && parsingVH == true) {
					virtualHosts.put(vh.getServerName(), vh);
					if (defaultVHost == null)
						defaultVHost = vh;
					vh = null;
					parsingVH = false;
				} else if (parsingVH == true) {
					if (st.contains("DocumentRoot")) {
						int idx = st.indexOf("DocumentRoot") + 13;
						String docRoot = st.substring(idx).trim();
						// igore the quotation mark if any
						if (docRoot.charAt(0) == '\"' && docRoot.charAt(docRoot.length() - 1) == '\"')
							docRoot = docRoot.substring(1, docRoot.length() - 1);
						vh.setDocRoot(docRoot);
					} else if (st.contains("ServerName")) {
						int idx = st.indexOf("ServerName") + 11;
						vh.setServerName(st.substring(idx).trim());
					}
				}
			}
		} catch (FileNotFoundException e) {
			Util.panic(2, "configuration file does not exist!");
		} catch (IOException e) {
			Util.panic(3, "cannot read configuation file!");
		} catch (NumberFormatException e) {
			Util.panic(4, "cannot parse listen port!");
		}
		Util.DEBUG(virtualHosts.toString());
	}

}
