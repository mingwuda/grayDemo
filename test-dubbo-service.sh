#!/bin/bash

# 测试Dubbo服务调用
# 确保服务都已启动

BASE_URL="http://localhost:8081/api/dubbo"

echo "=== Dubbo服务调用测试 ==="
echo

# 测试Dubbo服务调用（provider端控制，无需cookie）
echo "测试Dubbo服务调用:"
curl -s "$BASE_URL/test?consumerName=test-consumer"
echo
echo

echo "=== 测试完成 ==="

# 注意：现在provider端的上线/下线由ProviderServiceManager控制
# 可以通过修改Zookeeper中的/release/status路径来控制provider状态