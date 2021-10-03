#!/bin/bash

rm *.class
rm ThreadHTTPServer.jar
javac -cp commons-cli-1.4.jar ThreadHTTPServer.java ThreadHTTPRequestHandler.java VirtualHost.java Util.java
if [[ -f "ThreadHTTPServer.class" ]] && [[ -f "ThreadHTTPRequestHandler.class" ]] && [[ -f "VirtualHost.class" ]] && [[ -f "Util.class" ]]
then
	jar cfm ThreadHTTPServer.jar MANIFEST_THREAD.MF *.class
	rm *.class
fi