#!/bin/bash

# Docker环境下的发布状态管理系统测试
# 确保docker-compose服务正在运行

GRAY_CONSUMER_URL="http://localhost:8081/api/release"
PROD_CONSUMER_URL="http://localhost:8082/api/release"

echo "=== Docker环境发布状态管理系统测试 (3种状态版本) ==="
echo

# 等待服务启动
echo "等待服务启动..."
sleep 10

# 检查服务健康状态
echo "1. 检查服务健康状态:"
echo "灰度Consumer健康检查:"
curl -s "http://localhost:8081/actuator/health"  .
echo
echo "生产Consumer健康检查:"
curl -s "http://localhost:8082/actuator/health"  .
echo
echo

# 2. 获取所有可用状态（从灰度环境）
echo "2. 获取所有可用状态:"
curl -s "$GRAY_CONSUMER_URL/states"  .
echo
echo

# 3. 获取当前状态
echo "3. 获取当前状态:"
echo "灰度环境当前状态:"
curl -s "$GRAY_CONSUMER_URL/state"
echo
echo "生产环境当前状态:"
curl -s "$PROD_CONSUMER_URL/state"
echo
echo

# 4. 测试状态更新 - 仅灰度可访问
echo "4. 更新状态为 '仅灰度可访问':"
curl -s -X POST "$GRAY_CONSUMER_URL/state?stateName=GRAY_ACCESSABLE"
echo
echo

# 5. 查看仅灰度可访问的消费规则
echo "5. 查看 '仅灰度可访问' 状态的消费规则:"
echo "灰度环境规则:"
curl -s "$GRAY_CONSUMER_URL/state/GRAY_ACCESSABLE/rules"
echo
echo "生产环境规则:"
curl -s "$PROD_CONSUMER_URL/state/GRAY_ACCESSABLE/rules"
echo
echo

# 6. 更新状态为仅生产可访问
echo "6. 更新状态为 '仅生产可访问' (灰度环境停止消费):"
curl -s -X POST "$GRAY_CONSUMER_URL/state?stateName=PROD_ACCESSABLE"
echo
echo

# 等待状态同步
sleep 3

# 7. 查看仅生产可访问的消费规则
echo "7. 查看 '仅生产可访问' 状态的消费规则:"
echo "灰度环境规则:"
curl -s "$GRAY_CONSUMER_URL/state/PROD_ACCESSABLE/rules"
echo
echo "生产环境规则:"
curl -s "$PROD_CONSUMER_URL/state/PROD_ACCESSABLE/rules"
echo
echo

# 8. 更新状态为全部可访问
echo "8. 更新状态为 '全部可访问' (恢复所有环境消费):"
curl -s -X POST "$GRAY_CONSUMER_URL/state?stateName=ALL_ACCESSABLE"
echo
echo

# 等待状态同步
sleep 3

# 9. 查看全部可访问的消费规则
echo "9. 查看 '全部可访问' 状态的消费规则:"
echo "灰度环境规则:"
curl -s "$GRAY_CONSUMER_URL/state/ALL_ACCESSABLE/rules"
echo
echo "生产环境规则:"
curl -s "$PROD_CONSUMER_URL/state/ALL_ACCESSABLE/rules"
echo
echo

# 14. 最终状态确认
echo "14. 最终状态确认:"
echo "灰度环境最终状态:"
curl -s "$GRAY_CONSUMER_URL/state"
echo
echo "生产环境最终状态:"
curl -s "$PROD_CONSUMER_URL/state"
echo
echo

echo "=== Docker环境测试完成 ==="
echo
echo "提示："
echo "- 灰度Consumer API: http://localhost:8081/api/release"
echo "- 生产Consumer API: http://localhost:8082/api/release"
echo "- ZooKeeper: localhost:2181"
echo "- RocketMQ: localhost:9876"