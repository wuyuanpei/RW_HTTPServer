#!/bin/bash

rm *.class
rm HTTPServer.jar
javac -cp commons-cli-1.4.jar HTTPServer.java HTTPRequestHandler.java VirtualHost.java Util.java
if [[ -f "HTTPServer.class" ]] && [[ -f "HTTPRequestHandler.class" ]] && [[ -f "VirtualHost.class" ]] && [[ -f "Util.class" ]]
then
	jar cfm HTTPServer.jar MANIFEST.MF *.class
	rm *.class
fi