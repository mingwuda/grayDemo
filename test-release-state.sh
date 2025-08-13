#!/bin/bash

# 测试发布状态管理系统
# 确保consumer服务正在运行在8081端口

BASE_URL="http://localhost:8081/api/release"

echo "=== 发布状态管理系统测试 (3种状态版本) ==="
echo

# 1. 获取所有可用状态
echo "1. 获取所有可用状态:"
curl -s "$BASE_URL/states" | jq .
echo
echo

# 2. 获取当前状态
echo "2. 获取当前状态:"
curl -s "$BASE_URL/state"
echo
echo

# 3. 测试状态更新 - 仅灰度可访问
  echo "3. 更新状态为 'GRAY_ACCESSABLE':"
  curl -s -X POST "$BASE_URL/state?stateName=GRAY_ACCESSABLE"
echo
echo

# 4. 查看仅灰度可访问的消费规则
echo "4. 查看 'GRAY_ACCESSABLE' 状态的消费规则:"
curl -s "$BASE_URL/state/GRAY_ACCESSABLE/rules"
echo
echo

# 5. 更新状态为仅生产可访问
  echo "5. 更新状态为 'PROD_ACCESSABLE':"
  curl -s -X POST "$BASE_URL/state?stateName=PROD_ACCESSABLE"
echo
echo

# 6. 查看仅生产可访问的消费规则
echo "6. 查看 'PROD_ACCESSABLE' 状态的消费规则:"
  curl -s "$BASE_URL/state/PROD_ACCESSABLE/rules"
echo
echo

# 7. 更新状态为全部可访问
  echo "7. 更新状态为 'ALL_ACCESSABLE':"
  curl -s -X POST "$BASE_URL/state?stateName=ALL_ACCESSABLE"
echo
echo

# 8. 查看全部可访问的消费规则
echo "8. 查看 'ALL_ACCESSABLE' 状态的消费规则:"
curl -s "$BASE_URL/state/ALL_ACCESSABLE/rules"
echo
echo

# 10. 查看GRAY_ACCESSABLE的消费规则
echo "10. 查看 'GRAY_ACCESSABLE' 状态的消费规则:"
curl -s "$BASE_URL/state/GRAY_ACCESSABLE/rules"
echo
echo

# 11. 更新状态为ALL_ACCESSABLE
  echo "11. 更新状态为 'ALL_ACCESSABLE':"
  curl -s -X POST "$BASE_URL/state?stateName=ALL_ACCESSABLE"
echo
echo

# 12. 查看ALL_ACCESSABLE的消费规则
echo "12. 查看 'ALL_ACCESSABLE' 状态的消费规则:"
curl -s "$BASE_URL/state/ALL_ACCESSABLE/rules"
echo
echo

# 13. 最终状态确认
echo "13. 最终状态确认:"
curl -s "$BASE_URL/state"
echo
echo

echo "=== 测试完成 ==="