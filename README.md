# RW_HTTPServer/1.0

Please first render this ``README`` file for the convenience to read

- Author: Richard Wu
- Date: Sun, 3 Oct 2021 19:41:28 GMT

The first section describes the basic design of the http server (i.e., part 1)

The second section describes Threadpool design and Select-Multiplexing design (i.e., part 2a and 2b)

Please feel free to contact me at richard.wu@yale.edu if you have any question about testing this server.

## Basic HTTP Server for Part 1
### How to get started?

- Please run the command:
```
./run.sh
```
You should have a running server with ``-config ./config/httpd.conf``
### Files/Directories
#### Java file
- ``HTTPServer.java``: the main class
- ``HTTPRequestHandler.java``: handle the request
- ``VirtualHost.java``: virtual host class
- ``Util.java``: util functions
#### Jar file
- ``HTTPServer.jar``: the jar file to run. It can be produced by ``run.sh`` or ``compile.sh``
- ``commons-cli-1.4.jar``: the API for parsing command line argument (e.g., ``-config`` flag)
#### Shell script file
- ``compile.sh``: compile the java file to class file to jar file
- ``run.sh``: compile the java file to class file to jar file, and then run the jar file with ``-config ./config/httpd.conf``
- ``clean.sh``: do the cleanup
- ``test.sh``: script for testing the server, using the files in root and root2
#### Directories
- ``config``: host ``.conf`` configuration file
- ``root``: a root for a virtual host (used for testing)
- ``root2``: a root for a virtual host (used for testing)
#### Others
- ``MANIFEST.MF``: manifest file for ``HTTPServer.jar`` which sets up the classpath and main class
- ``README.md``: this file

### Implementation
- A per-thread, sequential processing handler server
- Use configuration file with Apache configuration style
- Static files that can be mapped by the server include ``.txt``, ``.html``, ``.jpg``. Other static files will be ``text/plain``
- Requests for the files out of the root directory will get ``403``
- Support ``Host`` header to serve multiple websites (virtual hosts) where each has a different root directory
- The first vitual host is the one used by default if ``Host`` header is not specified or ``Host`` is not found
- Send ``index.html`` or ``index_m.html``, if any, when the URL is a directory (any ``User-Agent`` that has substring "iPhone" or "phone" (case insensitive) will get ``index_m.html`` first, then ``index.html``, then ``404``)
- **Note that content negotiation headers (such as ``Accept``) are ignored, so the clients may get a representation of resource that they do not want or cannot accept. The server does not change the representation of resource based on the negotiation headers**
- Support ``If-Modified-Since`` header
- Support CGI for both ``GET`` and ``POST``: the environment variables the server sets include ``QUERY_STRING``, ``REMOTE_*``, ``REQUEST_METHOD``, ``SERVER_*``, ``CONTENT_LENGTH``
- The stdin of the CGI program will be pumped with the data from ``POST`` request if any. The stdout of the CGI program will be sent to the client, and the ``Transfer-Encoding`` will be ``chunked``: the server will chunk the response line by line (each chunk will be the entire line with ``\n``)
- The server will concatenate the http status line and the headers with the response from the CGI program. Thus, the CGI program should only be responsible for the data
- Support caching with cache size specified in the configuration file. If ``CacheSize <cache size in KB>`` is not specified, then no cache will be supported. The cache operates under the principle of "first come, first served" and there is no replacement policy. If a file is cached and then modified, unfortunately the old version will still be returned
- Support Heartbeat Monitoring through a virtual URL ``/load``, ``200`` or ``503`` will be returned indicating available or busy. When the number of threads currently used by the server reaches a preset maximum, ``503`` will be returned

## High-performance HTTP Server for Part 2a & 2b
### Concurrent HTTP Server using Threadpool
#### How to get started?

- Please run the command:
```
./run_thread.sh
```
You should have a running server with ``-config ./config/httpd_thread.conf``
#### Files/Directories
##### Java file
- ``ThreadHTTPServer.java``: the main class and the main thread
- ``ThreadHTTPRequestHandler.java``: thread in the pool that handles the request
- ``VirtualHost.java``: virtual host class
- ``Util.java``: util functions
##### Jar file
- ``ThreadHTTPServer.jar``: the jar file to run. It can be produced by ``run_thread.sh`` or ``compile_thread.sh``
- ``commons-cli-1.4.jar``: the API for parsing command line argument (e.g., ``-config`` flag)
##### Shell script file
- ``compile_thread.sh``: compile the java file to class file to jar file
- ``run_thread.sh``: compile the java file to class file to jar file, and then run the jar file with ``-config ./config/httpd_thread.conf``
- ``clean.sh``: do the cleanup
- ``test.sh``: script for testing the server, using the files in root and root2
##### Others
- ``MANIFEST_THREAD.MF``: manifest file for ``ThreadHTTPServer.jar`` which sets up the classpath and main class

#### Implementation
- A concurrent server using a thread pool: the main thread adding connection socket into a queue and a fix number of worker threads getting socket from it (with wait/notify primitives)
- The key features for HTTP protocol of this server is exactly the same as the basic server
- Heartbeat Monitoring now will return ``200`` if and only if there is nothing in the connection socket queue, i.e., there is no connection waiting to be served.


