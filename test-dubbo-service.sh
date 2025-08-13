#!/bin/bash

# 测试Dubbo服务调用
# 确保服务都已启动

BASE_URL="http://localhost:8081/api/dubbo"

echo "=== Dubbo服务调用测试 ==="
echo

# 1. 测试无cookie时的随机调用
echo "1. 测试无cookie时的随机调用:"
curl -s "$BASE_URL/test?consumerName=test-consumer"
echo
echo

# 2. 测试带gray cookie的调用
echo "2. 测试带gray cookie的调用:"
curl -s -H "Cookie: GrayFlag=GRAY" "$BASE_URL/test?consumerName=gray-consumer"
echo
echo

# 3. 测试带prd cookie的调用
echo "3. 测试带prd cookie的调用:"
curl -s -H "Cookie: GrayFlag=PRD" "$BASE_URL/test?consumerName=prd-consumer"
echo
echo

echo "=== 测试完成 ==="