#!/bin/bash

# 启动 NameServer
echo "Starting NameServer..."
sh mqnamesrv &

# 等待 NameServer 启动
sleep 10

# 启动 Broker
echo "Starting Broker..."
sh mqbroker -c /home/rocketmq/broker.conf


# 等待 Broker 启动
sleep 15

# 创建主题
echo "Creating topics..."
sh mqadmin updateTopic -n localhost:9876 -c DefaultCluster -t PRD_TOPIC
sh mqadmin updateTopic -n localhost:9876 -c DefaultCluster -t GRAY_TOPIC


# 保持容器运行
echo "RocketMQ services started"
# 保持容器运行
tail -f /dev/null