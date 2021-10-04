/***
 * class SelectHTTPRequestHandler implements a handler for a socket channel
 */
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

class SelectHTTPRequestHandler {

	/*Socket connSocket;
	BufferedReader inFromClient;
	DataOutputStream outToClient;*/
	
	private static final int BUFFER_SIZE = 2048;

	ByteBuffer inBuffer;
	ByteBuffer outBuffer;
	
	StringBuilder request; // string buffer for the header of the request
	StringBuilder data; // string buffer for the data of the request

	StringBuilder outResponse; // string buffer for the response from the server to the client

	private enum State {
		READING_HEADER, READING_DATA, GENERATING_RESPONSE, RESPONSE_READY, RESPONSE_SENT
	}
	private State state;

	String filePath; //root dir + URL
	File file;

	VirtualHost myVH; // the current virtual host that is in use

	int requestType; //1: get; 2: post;
	public static final int GET_REQUEST = 1;
	public static final int POST_REQUEST = 2;

	int userAgent = UNKNOWN_USER; //1: phone user; 2: PC user; if the header is present in the request
	public static final int UNKNOWN_USER = 0;
	public static final int PHONE_USER = 1;
	public static final int PC_USER = 2;

	String query_string; // the string after ? if any

	boolean usingHeartbeatingMonitor = false; // whether or not GET /load

	Date ifModifiedSince; // if the header is present in the request

	int contentLength = -1; // for POST request

	public SelectHTTPRequestHandler() {
		state = State.READING_HEADER;
		inBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		outBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		request = new StringBuilder(BUFFER_SIZE);
		data = new StringBuilder(BUFFER_SIZE);
		outResponse = new StringBuilder(BUFFER_SIZE);
	}

	/*public void run() {

		// increment numThreads
		// we are using the same lock for both numThreads and cache
		synchronized(SelectHTTPRequestHandler.class){
			SelectHTTPServer.numThreads ++;
		}

		try {
			// doing all the setups based on request headers
			if(parseRequest() == -1){
				thread_end();
				return;
			}

			// using heatbeating monitor
			if(usingHeartbeatingMonitor) {
				hbMonitor();
				thread_end();
				return;
			}
			
			// Send back the file (headers and data). 
			// The function outputFile() does all the validity checking and may use CGI if
			// the file is an executable and may use cache if it is a static file.
			outputFile();
			thread_end();
			return;

		} catch (Exception e) {
			Util.DEBUG("Inernal exception generated!");
			outputError(500, "Internal Server Error");
			e.printStackTrace();
		}

		try{
			thread_end();
		} catch(Exception e){
			Util.DEBUG("Thread_end exception generated!");
		}

	}*/

	/* a simple state to record \n\r\n (i.e., the boundary between header & data)
	* this field cannot be put in the handleRead function as a temporary variable 
	* because \n\r\n may break in the middle and from two successive reads
	*/
	int countNRN = 0; 
	/**
	 * read data from key.channel into request (HTTP status line & header) and data (HTTP data)
	 * @param key
	 * @throws IOException
	 */
	public void handleRead(SelectionKey key) throws IOException {
		// the state must be either READING_HEADER or READING_DATA
		if(state != State.READING_HEADER && state != State.READING_DATA) {
			return;
		}

		SocketChannel client = (SocketChannel) key.channel();
		int readBytes = client.read(inBuffer);
		Util.DEBUG("handleRead: Read from " + client + " for " + readBytes + " Bytes to buffer " + inBuffer);

		if (readBytes == -1) { // end of stream
			state = State.GENERATING_RESPONSE;
			Util.DEBUG("handleRead: readBytes == -1");
		}

		inBuffer.flip(); // read input
		
		// read into request
		while (inBuffer.hasRemaining() && state == State.READING_HEADER) {
			char ch = (char) inBuffer.get();
			//Util.DEBUG("Ch: " + ch);
			request.append(ch);
			if(ch == '\n') countNRN++;
			else if(ch == '\r' && countNRN == 1) countNRN++;
			else countNRN = 0;

			// NNN (should not happen) or NRN
			if(countNRN == 3){
				state = State.READING_DATA;
			}
		} 

		// read into data
		while (inBuffer.hasRemaining() && state == State.READING_DATA) {
			char ch = (char) inBuffer.get();
			//Util.DEBUG("ChD: " + ch);
			data.append(ch);
		} 

		Util.DEBUG("<request>\n" + request.toString());
		Util.DEBUG("<data>\n" + data.toString());

		// we make sure that it's always clean when the buffer is used for the next time
		inBuffer.clear();

		if(contentLength == -1 && state == State.READING_DATA){
			int CLHeader = request.indexOf("Content-Length:");
			// GET request that has no Content-Length header
			if(CLHeader == -1){
				state = State.GENERATING_RESPONSE;
			} else {
				int nextRN = request.indexOf("\r\n", CLHeader);
				contentLength = Integer.parseInt(request.substring(CLHeader + 15, nextRN).trim());
			}
		}

		if(contentLength == data.length()) {
			state = State.GENERATING_RESPONSE;
		}
		Util.DEBUG("contentLength == " + contentLength);
		Util.DEBUG("The current state is " + (state == State.GENERATING_RESPONSE ? "GENERATING_RESPONSE" : "READING"));

		// generate response
		if(state == State.GENERATING_RESPONSE) {
			// turn off read
			turnOff(key, SelectionKey.OP_READ);

			// error message ready
			if(parseRequest() == -1) {
				state = State.RESPONSE_READY;
				turnOn(key, SelectionKey.OP_WRITE);
				return;
			}

			// using heatbeating monitor
			if(usingHeartbeatingMonitor) {
				hbMonitor();
				state = State.RESPONSE_READY;
				turnOn(key, SelectionKey.OP_WRITE);
				return;
			}

		}
	}
	
	public void handleWrite(SelectionKey key) throws IOException {}

	/**
	 * Parse the HTTP request
	 * @return 0 if successfully parsed the request, -1 otherwise
	 * @throws IOException
	 */
	int parseRequest() throws IOException{
		// each item in requestsArr is a line
		String requestsArr[] = request.toString().split("\\r\\n");
		String requestMessageLine = requestsArr[0];
		Util.DEBUG("Request: " + requestMessageLine);
		// If EOF is reached
		if(requestMessageLine == null) {
			outputError(400, "Bad Request");
			return -1;
		}
			
		// process the request
		String[] request = requestMessageLine.split("\\s");

		if (request.length < 2) {
			outputError(400, "Bad Request");
			return -1;
		}

		// parse request type
		if(request[0].trim().toUpperCase().equals("GET")){
			requestType = GET_REQUEST;
		} else if(request[0].trim().toUpperCase().equals("POST")){
			requestType = POST_REQUEST;
		} else {
			outputError(400, "Bad Request");
			return -1;
		}

		// parse URL to retrieve file name
		String urlName = request[1].trim();
		// if there exists any query string for CGI
		if(urlName.contains("?") && requestType == GET_REQUEST){
			int q_index = urlName.indexOf("?");
			query_string = urlName.substring(q_index + 1).trim();
			urlName = urlName.substring(0, q_index).trim();
		}

		if (urlName.startsWith("/") == true)
			urlName = urlName.substring(1);
		
		// read header
		String line;
		for(int i = 1; i < requestsArr.length; i++) {
			line = requestsArr[i];
			int idx = line.indexOf(":");
			if(idx == -1) {
				Util.DEBUG(line);
				outputError(400, "Bad Request");
				return -1;
			}
			String header = line.substring(0, idx).trim();
			String content = line.substring(idx + 1).trim();
			Util.DEBUG("<" + header + ":" + content + ">");

			// Host header
			if(header.toLowerCase().equals("host")){
				String host = content;
				// ignore port number if any
				if(host.contains(":"))
					host = content.substring(0, content.indexOf(":")).trim();
				// search the host in the HashMap
				if(SelectHTTPServer.virtualHosts.containsKey(host)){
					VirtualHost vh = SelectHTTPServer.virtualHosts.get(host);
					myVH = vh;
					String docRoot = vh.getDocRoot().trim();
					if(docRoot.endsWith("/")){
						filePath = docRoot + urlName;
					} else {
						filePath = docRoot + "/" + urlName;
					}
				} else {
					// use the default host
				}
			}
			// User-Agent header
			else if(header.toLowerCase().equals("user-agent")){
				if(content.toLowerCase().contains("iphone") || content.toLowerCase().contains("phone")){
					userAgent = PHONE_USER;
				} else {
					userAgent = PC_USER;
				}
			}
			// Content-Length header
			else if(header.toLowerCase().equals("content-length")){
				contentLength = Integer.parseInt(content);
			}
			// If-Modified-Since header
			else if(header.toLowerCase().equals("if-modified-since")){
				SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z");
				sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
				try {
					ifModifiedSince = sdf.parse(content);
				} catch(ParseException e){
					outputError(400, "Bad Request");
					return -1;
				}
				Util.DEBUG("ifModifiedSince:" + ifModifiedSince.toString());
			}
		}

		// If no Host header has been found
		if(filePath == null){
			myVH = SelectHTTPServer.defaultVHost;
			String docRoot = SelectHTTPServer.defaultVHost.getDocRoot().trim();
			if(docRoot.endsWith("/")){
				filePath = docRoot + urlName;
			} else {
				filePath = docRoot + "/" + urlName;
			}
		}

		// set heartbeating monitor
		if(urlName.equals("load")){
			usingHeartbeatingMonitor = true;
		}
		
		return 0;
	}

	/**
	 * return a header telling the client whether the service is available
	 */
	private void hbMonitor() throws IOException {
		OperatingSystemMXBean osmxb = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		double cpu = osmxb.getSystemCpuLoad();
		Util.DEBUG("load:" + cpu);
		if(cpu < SelectHTTPServer.MAX_CPU_USAGE){
			outResponse.append("HTTP/1.1 200 OK\r\n");
		} else {
			outResponse.append("HTTP/1.1 503 Service Unavailable\r\n");
		}
		
	}

	/**
	 * Put error message to the outResponse string buffer
	 * @param errCode status code
	 * @param errMsg error message
	 */
	private void outputError(int errCode, String errMsg) {
		outResponse.append("HTTP/1.1 " + errCode + " " + errMsg + "\r\n");
	}

	/* Turn on/off a key's interest */
	private void turnOn(SelectionKey key, int op) {
		int nextState = key.interestOps();
		nextState = nextState | op;
		key.interestOps(nextState);
	}

	private void turnOff(SelectionKey key, int op) {
		int nextState = key.interestOps();
		nextState = nextState & ~op;
		key.interestOps(nextState);
	}
}