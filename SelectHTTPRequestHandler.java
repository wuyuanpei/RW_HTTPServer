/***
 * class SelectHTTPRequestHandler implements a handler for a socket channel
 */
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

class SelectHTTPRequestHandler {
	
	// BUFFER_SIZE should at least fit the response status line & headers
	private static final int BUFFER_SIZE = 4096;

	ByteBuffer inBuffer;
	/* The status line and headers will be put into outBuffer directly without bound checking
	 * so BUFFER_SIZE need to be large enough.
	 * The data part will be put into outBuffer with bound checking. If outBuffer is full,
	 * the data will be put into it in the next round
	 */
	ByteBuffer outBuffer;
	
	// An array for the file
	byte[] fileInBytes;
	int fileInBytesIdx = 0; // start from where in fileInBytes to put into outBuffer
	
	StringBuilder request; // string buffer for the header of the request
	StringBuilder data; // string buffer for the data of the request

	public enum State {
		READING_HEADER, READING_DATA, GENERATING_RESPONSE, RESPONSE_READY, LAST_RESPONSE_READY, RESPONSE_SENT
	}
	public State state;

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
	}

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

		if(contentLength != -1 && data.length() > contentLength){
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
				state = State.LAST_RESPONSE_READY;
				turnOn(key, SelectionKey.OP_WRITE);
				return;
			}

			// using heatbeating monitor
			if(usingHeartbeatingMonitor) {
				hbMonitor();
				state = State.LAST_RESPONSE_READY;
				turnOn(key, SelectionKey.OP_WRITE);
				return;
			}

			// Put the file (headers and data) into out response
			// The function outputFile() does all the validity checking and may use CGI if
			// the file is an executable and may use cache if it is a static file.
			if(outputFile(key) == -1) {
				state = State.LAST_RESPONSE_READY;
				turnOn(key, SelectionKey.OP_WRITE);
				return;
			}
			state = State.RESPONSE_READY;
			turnOn(key, SelectionKey.OP_WRITE);
			return;

		}
	}
	
	public void handleWrite(SelectionKey key) throws IOException {

		// must in the correct state
		if(state != State.RESPONSE_READY && state != State.LAST_RESPONSE_READY)
			return;

		outBuffer.flip();

		SocketChannel client = (SocketChannel) key.channel();
		int writeBytes = client.write(outBuffer);
		Util.DEBUG("handleWrite: write " + writeBytes + " bytes; after write " + outBuffer);
		
		// test whether client.write(outBuffer) cleans the outBuffer
		if(outBuffer.hasRemaining()) {
			Util.DEBUG("write does not clear outBuffer!");
			return; // wait for next write
		}

		outBuffer.clear(); // for next write

		if (state == State.LAST_RESPONSE_READY) {
			Util.DEBUG("handleWrite: responseSent");
			state = State.RESPONSE_SENT;
			//turnOff(key, SelectionKey.OP_WRITE);
			client.close();
			key.cancel();
			return;
		}

		if(state == State.RESPONSE_READY){
			if(outputResponseBody() == -1){
				state = State.LAST_RESPONSE_READY;
			}
		}

	}

	/**
	 * Send the file back to the client
	 * The method will first check the validity of the file, then try to 
	 * return it through cache, and then return it through the file system
	 * @return -1 if all data is put into outBuffer, 0 otherwise (more rounds of writes are needed)
	 * @throws IOException
	 */
	private int outputFile(SelectionKey key) throws IOException {
		
		file = new File(filePath);

		// use index.html or index_m.html if file is a directory
		if(file.isDirectory()){
			if(userAgent == PC_USER || userAgent == UNKNOWN_USER) {
				file = new File(file.getCanonicalPath() + "/index.html");
			} else if(userAgent == PHONE_USER){
				String fileDir = file.getCanonicalPath();
				file = new File(fileDir + "/index_m.html");
				if(!file.exists()){
					file = new File(fileDir + "/index.html");
				}
			}
		}

		// the file must be contained in the root directory
		File rootDir = new File(myVH.getDocRoot());
		if(!file.getCanonicalPath().startsWith(rootDir.getCanonicalPath())){
			Util.DEBUG(file.getCanonicalPath() + " is out of root directory!");
			outputError(403, "Forbidden");
			return -1;
		}

		// test whether the file exists
		if(!file.isFile()){
			Util.DEBUG(file.getCanonicalPath() + " does not exist!");
			outputError(404, "Not Found");
			return -1;
		}

		// ifModifiedSince is not null
		if(ifModifiedSince != null) {
			Date lastModifiedTime = new Date(file.lastModified());
			// ignore the millisecond
			if(ifModifiedSince.getTime() / 1000 >= lastModifiedTime.getTime() / 1000){
				// not exactly an error, but we can use the same interface
				outputError(304, "Not Modified"); 
				return -1;
			}
		}

		Util.DEBUG("File: " + file.getCanonicalPath());

		// If the file is executable, use CGI
		if(file.canExecute()){
			Util.DEBUG("This file is executable. Use CGI!");
			CGI(key);
			return 0;
		}

		// POST request should only use CGI. (should not get a file)
		if(requestType == POST_REQUEST){
			outputError(403, "Forbidden");
			return -1;
		}

		outputResponseHeader();
		writeBytes("\r\n");

		getResponseBodyFromCache();
		return outputResponseBody();
	}

	/**
	 * put the file into outBuffer
	 * @return -1 if all done; 0 if more rounds are needed
	 * @throws IOException
	 */
	private int outputResponseBody() throws IOException {
		int fileLength;

		// If the file is not in cache
		if(fileInBytes == null) {
			// read file content
			fileLength = (int)file.length();
			FileInputStream fileStream = new FileInputStream(file);

			fileInBytes = new byte[fileLength];
			fileStream.read(fileInBytes);
			fileStream.close();
		
			// put the file content into cache if possible
			// do nothing if the cache is full. There is no replacement policy
			if(SelectHTTPServer.cache != null && fileInBytes.length + SelectHTTPServer.cacheCurrentSize <= SelectHTTPServer.cacheMaxSize){
				SelectHTTPServer.cache.put(file, fileInBytes);
				SelectHTTPServer.cacheCurrentSize += fileInBytes.length;
			}
		} else {
			fileLength = fileInBytes.length;
		}

		// we can write maxWrite bytes
		int maxWrite = outBuffer.remaining();
		if(maxWrite >= fileLength - fileInBytesIdx){
			outBuffer.put(fileInBytes, fileInBytesIdx, fileLength - fileInBytesIdx);
			return -1;
		} else {
			outBuffer.put(fileInBytes, fileInBytesIdx, maxWrite);
			fileInBytesIdx += maxWrite;
			return 0;
		}
	}

	/**
	 * get the file directly from cache if possible
	 * @throws IOException
	 */
	private void getResponseBodyFromCache() throws IOException {
		// note that if the content get cached and then get modified, the old content will still be returned
		if(SelectHTTPServer.cache != null && SelectHTTPServer.cache.containsKey(file)){
			Util.DEBUG("cacheSize:" + SelectHTTPServer.cacheCurrentSize + "; cacheMaxSize:" + SelectHTTPServer.cacheMaxSize);
			fileInBytes = SelectHTTPServer.cache.get(file);
		}	
	}

	private void outputResponseHeader() throws IOException {
		writeBytes("HTTP/1.1 200 OK\r\n");

		// Date header
		Date currentTime = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		writeBytes("Date: " + sdf.format(currentTime) + "\r\n");

		// Server header
		writeBytes("Server: " + SelectHTTPServer.SERVER_NAME + "\r\n");

		// Last-Modified header
		Date lastModifiedTime = new Date(file.lastModified());
		writeBytes("Last-Modified: " + sdf.format(lastModifiedTime) + "\r\n");

		// Content-Type header
		if (file.getCanonicalPath().endsWith(".jpg"))
			writeBytes("Content-Type: image/jpeg\r\n");
		else if (file.getCanonicalPath().endsWith(".gif"))
			writeBytes("Content-Type: image/gif\r\n");
		else if (file.getCanonicalPath().endsWith(".html") || file.getCanonicalPath().endsWith(".htm"))
			writeBytes("Content-Type: text/html\r\n");
		else // including .txt file
			writeBytes("Content-Type: text/plain\r\n");

		// Content-Length header
		writeBytes("Content-Length: " + (int)file.length()+ "\r\n");
	}

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
	 * file is an executable and use CGI to execute it
	 */
	public void CGI(SelectionKey key) throws IOException{
		// build the process
		ProcessBuilder pb = new ProcessBuilder(file.getCanonicalPath());
 		Map<String, String> env = pb.environment();

		// set environment variables
		if(query_string != null)
 			env.put("QUERY_STRING", query_string);
		else
			env.put("QUERY_STRING", "");

		if(requestType == GET_REQUEST){
			env.put("REQUEST_METHOD", "GET");
			env.put("CONTENT_LENGTH", "");
		} else if(requestType == POST_REQUEST){
			env.put("REQUEST_METHOD", "POST");
			env.put("CONTENT_LENGTH", contentLength == -1 ? "" : "" + contentLength);
		}

		env.put("SERVER_NAME", myVH.getServerName());
		env.put("SERVER_PORT", "" + SelectHTTPServer.serverPort);
		env.put("SERVER_PROTOCOL", "HTTP/1.1");
		env.put("SERVER_SOFTWARE", SelectHTTPServer.SERVER_NAME);
		env.put("REMOTE_ADDR", ((SocketChannel)key.channel()).socket().getInetAddress().getHostAddress());
		env.put("REMOTE_HOST", ((SocketChannel)key.channel()).socket().getInetAddress().getHostName());
		env.put("REMOTE_IDENT", ""); // the authentication env variale is ignored
		env.put("REMOTE_USER", ""); // the authentication env variale is ignored

		// start the process and connect IO
		Process p = pb.start();
		InputStream inputStream = p.getInputStream();
		BufferedReader r = new BufferedReader(new InputStreamReader(inputStream));
		if(requestType == POST_REQUEST && contentLength != -1) {
			DataOutputStream outstr = new DataOutputStream(p.getOutputStream());
			outstr.writeBytes(data.toString());
			outstr.flush();
		}
		// send response and header
		writeBytes("HTTP/1.1 200 OK\r\n");

		// Date header
		Date currentTime = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		writeBytes("Date: " + sdf.format(currentTime) + "\r\n");

		// Server header
		writeBytes("Server: " + SelectHTTPServer.SERVER_NAME + "\r\n");

		// Content-Type header
		writeBytes("Content-Type: text/plain\r\n");

		// Transfer-Encoding header
		writeBytes("Transfer-Encoding: chunked\r\n");

		writeBytes("\r\n");

		// Output from the CGI script
		// here, we ignore the header output by the CGI program and treat it the same as data
		StringBuilder cgiOut = new StringBuilder();
		String line = r.readLine();
		while (line != null) {
			//Util.DEBUG(line);
			int lineLen = line.length();
			cgiOut.append(Integer.toHexString(lineLen + 1) + "\r\n");
			cgiOut.append(line + "\n\r\n"); //\n is appended because readLine discarded it
			line = r.readLine();
		}
		cgiOut.append("0\r\n\r\n");
		fileInBytes = cgiOut.toString().getBytes();
	}

	/**
	 * return a header telling the client whether the service is available
	 */
	private void hbMonitor() throws IOException {
		OperatingSystemMXBean osmxb = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		double cpu = osmxb.getSystemCpuLoad();
		Util.DEBUG("load:" + cpu);
		if(cpu < SelectHTTPServer.MAX_CPU_USAGE){
			writeBytes("HTTP/1.1 200 OK\r\n");
		} else {
			writeBytes("HTTP/1.1 503 Service Unavailable\r\n");
		}
	}

	/**
	 * Put error message to the outResponse string buffer
	 * @param errCode status code
	 * @param errMsg error message
	 */
	private void outputError(int errCode, String errMsg) {
		writeBytes("HTTP/1.1 " + errCode + " " + errMsg + "\r\n");
	}

	/**
	 * Write str to outBuffer
	 * @param str
	 */
	private void writeBytes(String str) {
		try{
			outBuffer.put(str.getBytes());
		} catch (BufferOverflowException e) {
			e.printStackTrace();
			Util.DEBUG("outBuffer overflow happens! Some data is lost!");
		}
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