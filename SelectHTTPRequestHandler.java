/***
 * class SelectHTTPRequestHandler implements a thread that can handle most of the HTTP requests
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;

class SelectHTTPRequestHandler implements Runnable {

	Socket connSocket;
	BufferedReader inFromClient;
	DataOutputStream outToClient;

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

	public SelectHTTPRequestHandler(Socket connectionSocket) throws IOException{

		this.connSocket = connectionSocket;

		inFromClient = new BufferedReader(new InputStreamReader(connSocket.getInputStream()));

		outToClient = new DataOutputStream(connSocket.getOutputStream());
	}

	@Override
	public void run() {

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

	}

	/**
	 * Do the cleanup of the thread
	 */
	private void thread_end() throws IOException {
		synchronized(SelectHTTPRequestHandler.class){
			SelectHTTPServer.numThreads --;
		}
		connSocket.close();
	}

	/**
	 * Parse the HTTP request
	 * @return 0 if successfully parsed the request, -1 otherwise
	 * @throws IOException
	 */
	int parseRequest() throws IOException{
		String requestMessageLine = inFromClient.readLine();
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
		String line = inFromClient.readLine();
		while (line != null && !line.equals("")) {
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
			line = inFromClient.readLine();
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
	 * Send the file back to the client
	 * The method will first check the validity of the file, then try to 
	 * return it through cache, and then return it through the file system
	 * @return 0 if successful, -1 otherwise
	 * @throws IOException
	 */
	private int outputFile() throws IOException {
		
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
			CGI();
			return 0;
		}

		// POST request should only use CGI. (should not get a file)
		if(requestType == POST_REQUEST){
			outputError(403, "Forbidden");
			return -1;
		}

		outputResponseHeader();
		outToClient.writeBytes("\r\n");
		if(outputResponseBodyFromCache() == -1)
			outputResponseBody();
		return 0;
	}

	private void outputResponseHeader() throws IOException {
		outToClient.writeBytes("HTTP/1.1 200 OK\r\n");

		// Date header
		Date currentTime = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		outToClient.writeBytes("Date: " + sdf.format(currentTime) + "\r\n");

		// Server header
		outToClient.writeBytes("Server: " + SelectHTTPServer.SERVER_NAME + "\r\n");

		// Last-Modified header
		Date lastModifiedTime = new Date(file.lastModified());
		outToClient.writeBytes("Last-Modified: " + sdf.format(lastModifiedTime) + "\r\n");

		// Content-Type header
		if (file.getCanonicalPath().endsWith(".jpg"))
			outToClient.writeBytes("Content-Type: image/jpeg\r\n");
		else if (file.getCanonicalPath().endsWith(".gif"))
			outToClient.writeBytes("Content-Type: image/gif\r\n");
		else if (file.getCanonicalPath().endsWith(".html") || file.getCanonicalPath().endsWith(".htm"))
			outToClient.writeBytes("Content-Type: text/html\r\n");
		else // including .txt file
			outToClient.writeBytes("Content-Type: text/plain\r\n");

		// Content-Length header
		outToClient.writeBytes("Content-Length: " + (int)file.length()+ "\r\n");
	}

	private void outputResponseBody() throws IOException {
		int fileLength = (int)file.length();
		// send file content
		FileInputStream fileStream = new FileInputStream(file);

		byte[] fileInBytes = new byte[fileLength];
		fileStream.read(fileInBytes);
		outToClient.write(fileInBytes, 0, fileLength);
		fileStream.close();
		// put the file content into cache if possible
		synchronized(SelectHTTPRequestHandler.class){
			// do nothing if the cache is full. There is no replacement policy
			if(SelectHTTPServer.cache != null && fileInBytes.length + SelectHTTPServer.cacheCurrentSize <= SelectHTTPServer.cacheMaxSize){
				SelectHTTPServer.cache.put(file, fileInBytes);
				SelectHTTPServer.cacheCurrentSize += fileInBytes.length;
			}
		}
	}
	
	/**
	 * Output the file directly from cache if possible, otherwise return -1
	 * @return 0 if succeed, otherwise -1
	 * @throws IOException
	 */
	private int outputResponseBodyFromCache() throws IOException {
		// note that only one thread can enter the critical section
		synchronized(SelectHTTPRequestHandler.class){
			// note that if the content get cached and then get modified, the old content will still be returned
			if(SelectHTTPServer.cache != null && SelectHTTPServer.cache.containsKey(file)){
				Util.DEBUG("cacheSize:" + SelectHTTPServer.cacheCurrentSize + "; cacheMaxSize:" + SelectHTTPServer.cacheMaxSize);
				byte []content = SelectHTTPServer.cache.get(file);
				outToClient.write(content, 0, content.length);
				return 0;
			}
			return -1;
		}
	}
	
	/**
	 * return a header telling the client whether the service is available
	 */
	private void hbMonitor() throws IOException {
		synchronized(SelectHTTPRequestHandler.class){
			Util.DEBUG("current # of threads: " + SelectHTTPServer.numThreads);
			if(SelectHTTPServer.numThreads < SelectHTTPServer.MAX_THREAD){
				outToClient.writeBytes("HTTP/1.1 200 OK\r\n");
			} else {
				outToClient.writeBytes("HTTP/1.1 503 Service Unavailable\r\n");
			}
		}
	}

	/**
	 * file is an executable and use CGI to execute it
	 */
	public int CGI() throws IOException{
		// build the process
		ProcessBuilder pb = new ProcessBuilder(file.getCanonicalPath());
 		Map<String, String> env = pb.environment();

		// post data will be the stdin of CGI script, so put it in a string
		char buf[] = null;
		if(requestType == POST_REQUEST && contentLength != -1) {
			buf = new char[contentLength];
			// read data (note that in our implementation POST for CGI is the only request that uses data)
			// also note the we cannot use readLine, because the client may not send \n or \r or EOF
			// even though buf is larger, you can only read contentLength characters
			if(inFromClient.read(buf) != contentLength){ 
				outputError(400, "Bad Request");
				Util.DEBUG("actual content length is not equal to the length specified!");
				return -1;
			}
		}

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

		env.put("REMOTE_ADDR", connSocket.getInetAddress().getHostAddress());
		env.put("REMOTE_HOST", connSocket.getInetAddress().getHostName());
		env.put("REMOTE_IDENT", ""); // the authentication env variale is ignored
		env.put("REMOTE_USER", ""); // the authentication env variale is ignored

		// start the process and connect IO
		Process p = pb.start();
		InputStream inputStream = p.getInputStream();
		BufferedReader r = new BufferedReader(new InputStreamReader(inputStream));
		if(requestType == POST_REQUEST && contentLength != -1) {
			DataOutputStream outstr = new DataOutputStream(p.getOutputStream());
			outstr.writeBytes(new String(buf));
			outstr.flush();
		}
		// send response and header
		outToClient.writeBytes("HTTP/1.1 200 OK\r\n");

		// Date header
		Date currentTime = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss z");
		sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		outToClient.writeBytes("Date: " + sdf.format(currentTime) + "\r\n");

		// Server header
		outToClient.writeBytes("Server: " + SelectHTTPServer.SERVER_NAME + "\r\n");

		// Content-Type header
		outToClient.writeBytes("Content-Type: text/plain\r\n");

		// Transfer-Encoding header
		outToClient.writeBytes("Transfer-Encoding: chunked\r\n");

		outToClient.writeBytes("\r\n");

		// Output from the CGI script
		// here, we ignore the header output by the CGI program and treat it the same as data
		String line = r.readLine();
		while (line != null) {
			//Util.DEBUG(line);
			int lineLen = line.length();
			outToClient.writeBytes(Integer.toHexString(lineLen + 1) + "\r\n");
			outToClient.writeBytes(line + "\n\r\n"); //\n is appended because readLine discarded it
			line = r.readLine();
		}
		outToClient.writeBytes("0\r\n\r\n");
		return 0;
	}

	/**
	 * Send error message to the client
	 * @param errCode status code
	 * @param errMsg error message
	 */
	void outputError(int errCode, String errMsg) {
		try {
			outToClient.writeBytes("HTTP/1.1 " + errCode + " " + errMsg + "\r\n");
		} catch (Exception e) {
		}
	}
	
}