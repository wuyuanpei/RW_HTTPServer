#!/usr/bin/perl -w
$company = $ENV{'QUERY_STRING'};
print "<html>";
print "<h1>Hello! The price is ";
if ($company =~ /appl/) {
my $var_rand = rand();
print 450 + 10 * $var_rand;
} else {
print "150";
}
print "</h1>\n";

print "QUERY_STRING:".$company."\n";
print "REQUEST_METHOD:".$ENV{'REQUEST_METHOD'}."\n";
print "REMOTE_ADDR:".$ENV{'REMOTE_ADDR'}."\n";
print "REMOTE_HOST:".$ENV{'REMOTE_HOST'}."\n";
print "REMOTE_IDENT:".$ENV{'REMOTE_IDENT'}."\n";
print "REMOTE_USER:".$ENV{'REMOTE_USER'}."\n";
print "SERVER_NAME:".$ENV{'SERVER_NAME'}."\n";
print "SERVER_PORT:".$ENV{'SERVER_PORT'}."\n";
print "SERVER_PROTOCOL:".$ENV{'SERVER_PROTOCOL'}."\n";
print "SERVER_SOFTWARE:".$ENV{'SERVER_SOFTWARE'}."\n";
print "CONTENT_LENGTH:".$ENV{'CONTENT_LENGTH'}."\n";
read(STDIN, $buffer, $ENV{'CONTENT_LENGTH'})."\n";
print "STDIN:".$buffer."\n";;
print "\n</html>";

