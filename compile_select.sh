#!/bin/bash

rm *.class
rm SelectHTTPServer.jar
javac -cp commons-cli-1.4.jar SelectHTTPServer.java SelectHTTPRequestHandler.java VirtualHost.java Util.java
if [[ -f "SelectHTTPServer.class" ]] && [[ -f "SelectHTTPRequestHandler.class" ]] && [[ -f "VirtualHost.class" ]] && [[ -f "Util.class" ]]
then
	jar cfm SelectHTTPServer.jar MANIFEST_SELECT.MF *.class
	rm *.class
fi