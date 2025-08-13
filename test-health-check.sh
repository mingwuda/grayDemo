#!/bin/bash

echo "=== Testing Health Check After Fix ==="
echo

echo "1. Testing consumer_gray health check:"
curl -s http://localhost:8081/health | jq .
echo

echo "2. Testing consumer_prd health check:"
curl -s http://localhost:8082/health | jq .
echo

echo "3. Getting current release state:"
curl -s http://localhost:8081/api/release/current
echo
echo

echo "4. Getting all available states:"
curl -s http://localhost:8081/api/release/states
echo
echo

echo "5. Checking Docker container health status:"
docker-compose ps
echo

echo "=== Health Check Test Complete ==="