#!/bin/bash
echo "========="$3"==========" >> benchmark_result.txt
sleep $2
ab -n $1 -c 1 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 2 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 3 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 4 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 5 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 6 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 7 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 8 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 9 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 10 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 11 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 13 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 15 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 17 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 20 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 25 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 30 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 35 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 40 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 45 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
sleep $2
ab -n $1 -c 50 http://localhost:6789/index.html | grep "Transfer rate" | tail -c 30 | head -c 7 >> benchmark_result.txt
echo "" >> benchmark_result.txt
echo "" >> benchmark_result.txt
cat benchmark_result.txt
