#!/bin/bash

rm *.class
rm SelectHTTPServer.jar
javac -cp commons-cli-1.4.jar SelectHTTPServer.java SelectHTTPRequestHandler.java VirtualHost.java Util.java Command.java ShutdownCommand.java CommandThread.java
if [[ -f "SelectHTTPServer.class" ]] && [[ -f "SelectHTTPRequestHandler.class" ]] && [[ -f "VirtualHost.class" ]] && [[ -f "Util.class" ]] && [[ -f "Command.class" ]] && [[ -f "CommandThread.class" ]] && [[ -f "ShutdownCommand.class" ]]
then
	jar cfm SelectHTTPServer.jar MANIFEST_SELECT.MF *.class
	rm *.class
	java -jar SelectHTTPServer.jar -config ./config/httpd.conf
fi