#!/usr/bin/perl -w
if ($ENV{'REQUEST_METHOD'} eq "POST") {
   read(STDIN, $buffer, $ENV{'CONTENT_LENGTH'});
} else {
   $buffer = $ENV{'QUERY_STRING'};
}
$upper_str = uc $buffer;
print $upper_str."\n";
print $ENV{'CONTENT_LENGTH'} # should be "" for GET
