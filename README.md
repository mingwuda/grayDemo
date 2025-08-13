# Docker容器化部署指南

## 概述

本文档详细介绍如何使用Docker Compose部署基于ZooKeeper的动态消费控制系统，包含RocketMQ消息队列、Dubbo服务、Elastic-Job分布式任务调度等功能。

## 服务架构

### 容器服务列表

| 服务名称 | 容器名称 | 端口映射 | 说明 |
|----------|----------|----------|------|
| nginx | nginx | 80:80 | 负载均衡器 |
| zookeeper | zookeeper | 2181:2181 | 分布式协调服务 |
| rocketmq | rocketmq | 9876:9876, 10909:10909, 10911:10911 | 消息队列服务 |
| product_gray | product_gray | 20881:20880 | 灰度环境生产者 |
| product_prd | product_prd | 20882:20880 | 生产环境生产者 |
| consumer_gray | consumer_gray | 8081:8080 | 灰度环境消费者 |
| consumer_prd | consumer_prd | 8082:8080 | 生产环境消费者 |

### 服务依赖关系

```
nginx
├── product_gray (Dubbo端口20881)
│   ├── rocketmq
│   └── zookeeper
└── product_prd (Dubbo端口20882)
    ├── rocketmq
    └── zookeeper

consumer_gray (HTTP端口8081)
├── rocketmq
├── zookeeper (Elastic-Job注册中心)
└── nginx (负载均衡)

consumer_prd (HTTP端口8082)
├── rocketmq
├── zookeeper (Elastic-Job注册中心)
└── nginx (负载均衡)
```

## 配置说明

### ZooKeeper配置

```yaml
zookeeper:
  image: docker.xuanyuan.run/library/zookeeper:latest
  container_name: zookeeper
  ports:
    - "2181:2181"
  environment:
    - ZOO_MY_ID=1
    - ZOO_SERVERS=server.1=0.0.0.0:2888:3888;2181
  volumes:
    - zookeeper_data:/data
    - zookeeper_datalog:/datalog
  healthcheck:
    test: ["CMD", "zkServer.sh", "status"]
    interval: 10s
    timeout: 5s
    retries: 3
```

### RocketMQ配置

```yaml
rocketmq:
  build:
    context: ./rocketmq
  container_name: rocketmq
  volumes:
    - ./rocketmq/store:/home/rocketmq/store
    - ./rocketmq/logs:/home/rocketmq/logs
  ports:
    - "9876:9876"
    - "10909:10909"
    - "10911:10911"
  user: "rocketmq"
```

### Producer服务配置

#### 灰度环境Producer
```yaml
product_gray:
  build:
    context: ./backends
    dockerfile: producer.Dockerfile
  container_name: product_gray
  environment:
    - NODE_TYPE=GRAY
    - ROCKETMQ_NAME_SERVER=rocketmq:9876
    - SPRING_PROFILES_ACTIVE=gray
    - ZOOKEEPER_CONNECT_STRING=zookeeper:2181
  ports:
    - "20881:20880"
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 3
```

#### 生产环境Producer
```yaml
product_prd:
  build:
    context: ./backends
    dockerfile: producer.Dockerfile
  container_name: product_prd
  environment:
    - NODE_TYPE=PRD
    - ROCKETMQ_NAME_SERVER=rocketmq:9876
    - SPRING_PROFILES_ACTIVE=prd
    - ZOOKEEPER_CONNECT_STRING=zookeeper:2181
  ports:
    - "20882:20880"
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 3
```

### Consumer服务配置

#### 灰度环境Consumer
```yaml
consumer_gray:
  build:
    context: ./backends
    dockerfile: consumer.Dockerfile
  container_name: consumer_gray
  ports:
    - "8081:8080"
  environment:
    - NODE_TYPE=GRAY_CONSUMER
    - ROCKETMQ_NAME_SERVER=rocketmq:9876
    - ZOOKEEPER_CONNECT_STRING=zookeeper:2181
    - SPRING_PROFILES_ACTIVE=gray
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
    interval: 10s
    timeout: 5s
    retries: 3
```

#### 生产环境Consumer
```yaml
consumer_prd:
  build:
    context: ./backends
    dockerfile: consumer.Dockerfile
  container_name: consumer_prd
  ports:
    - "8082:8080"
  environment:
    - NODE_TYPE=PRD_CONSUMER
    - ROCKETMQ_NAME_SERVER=rocketmq:9876
    - ZOOKEEPER_CONNECT_STRING=zookeeper:2181
    - SPRING_PROFILES_ACTIVE=prd
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
    interval: 10s
    timeout: 5s
    retries: 3
```

## 部署步骤

### 1. 准备工作

确保Docker和Docker Compose已安装：
```bash
docker --version
docker-compose --version
```

### 2. 构建镜像

```bash
# 构建所有服务镜像
docker-compose build

# 构建特定服务镜像
docker-compose build consumer_gray
```

### 3. 启动服务

```bash
# 启动所有服务（后台运行）
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看服务日志
docker-compose logs -f

# 启动特定服务
docker-compose up -d consumer_gray consumer_prd
```

### 4. 验证部署

#### 检查服务健康状态
```bash
# ZooKeeper健康检查
docker exec zookeeper zkServer.sh status

# RocketMQ健康检查
curl http://localhost:9876

# Producer服务健康检查
curl http://localhost:8080/actuator/health  # 在容器内
# 或通过端口映射检查
curl http://localhost:20881/actuator/health  # 灰度Producer
curl http://localhost:20882/actuator/health  # 生产Producer

# Consumer服务健康检查
curl http://localhost:8081/health  # 灰度Consumer
curl http://localhost:8082/health  # 生产Consumer
```

#### 检查ZooKeeper连接和Elastic-Job注册
```bash
# 连接到ZooKeeper客户端
docker exec -it zookeeper zkCli.sh

# 在ZooKeeper客户端中执行
ls /
get /release/status
ls /elastic-job-demo  # 检查Elastic-Job注册
```

#### 验证Elastic-Job调度
```bash
# 查看Consumer服务日志中的Elastic-Job执行日志
docker-compose logs -f consumer_gray | grep "DemoJob"
docker-compose logs -f consumer_prd | grep "DemoJob"
```

## 服务管理

### 启动和停止

```bash
# 启动所有服务
docker-compose up -d

# 停止所有服务
docker-compose down

# 停止并删除数据卷
docker-compose down -v

# 重启特定服务
docker-compose restart consumer_gray
docker-compose restart consumer_prd
```

### 日志查看

```bash
# 查看所有服务日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f consumer_gray
docker-compose logs -f consumer_prd
docker-compose logs -f zookeeper
docker-compose logs -f rocketmq

# 查看最近100行日志
docker-compose logs --tail=100 consumer_gray
```

### 服务扩缩容

```bash
# 扩展consumer服务实例
docker-compose up -d --scale consumer_gray=2

# 注意：由于端口冲突，需要修改docker-compose.yml配置
```

##### 配置管理

### 环境变量配置

可以通过`.env`文件管理环境变量：

```bash
# .env文件示例
ROCKETMQ_NAME_SERVER=rocketmq:9876
ZOOKEEPER_CONNECT_STRING=zookeeper:2181
ROCKETMQ_TOPIC=PRD_TOPIC
ROCKETMQ_TAG=*
NODE_TYPE=PRD_CONSUMER
```

### 动态配置更新

#### 使用Spring Cloud Config
```bash
# 刷新配置（需要Spring Cloud Config支持）
curl -X POST http://localhost:8081/actuator/refresh
curl -X POST http://localhost:8082/actuator/refresh
```

#### Elastic-Job配置调整
Elastic-Job的配置通过Java配置类动态读取，修改application.yml后重启对应服务：
```bash
docker-compose restart consumer_gray
docker-compose restart consumer_prd
```

### 数据持久化

#### ZooKeeper数据
- **数据卷**: `zookeeper_data`, `zookeeper_datalog`
- **挂载路径**: `/data`, `/datalog`
- **备份命令**: 
```bash
docker run --rm -v zookeeper_data:/data -v $(pwd)/backup:/backup alpine tar czf /backup/zookeeper_backup.tar.gz /data
```

#### RocketMQ数据
- **数据卷**: `./rocketmq/store`, `./rocketmq/logs`
- **清理数据**: 
```bash
sudo rm -rf rocketmq/store/* rocketmq/logs/*
```

### 故障排查

#### 常见命令
```bash
# 检查容器状态
docker-compose ps

# 查看容器资源使用
docker stats

# 进入容器调试
docker exec -it consumer_gray /bin/sh

# 查看容器IP
docker inspect consumer_gray | grep IPAddress

# 网络连通性测试
docker exec consumer_gray ping zookeeper
docker exec consumer_gray ping rocketmq

# 更新发布状态
curl -X POST "http://localhost:8081/api/release/state?stateName=GRAY_ACCESSABLE"
curl -X POST "http://localhost:8081/api/release/state?stateName=PROD_ACCESSABLE"
curl -X POST "http://localhost:8081/api/release/state?stateName=ALL_ACCESSABLE"
```

# 查看当前状态
curl "http://localhost:8081/api/release/state"
curl "http://localhost:8082/api/release/state"
```

## 测试验证

### 自动化测试

```bash
# 运行Docker环境测试脚本
./test-docker-release-state.sh
```

### 手动测试

```bash
# 1. 检查服务状态
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# 2. 获取可用状态
curl http://localhost:8081/api/release/states

# 3. 测试状态切换
curl -X POST "http://localhost:8081/api/release/state?state=GRAY_ACCESSABLE"
curl -X POST "http://localhost:8081/api/release/state?state=PROD_ACCESSABLE"
curl -X POST "http://localhost:8081/api/release/state?state=ALL_ACCESSABLE"

# 4. 验证当前状态
curl "http://localhost:8081/api/release/state"
curl "http://localhost:8082/api/release/state"
```

## 故障排查

### 常见问题

#### 1. 服务启动失败

```bash
# 查看服务状态
docker-compose ps

# 查看失败服务日志
docker-compose logs service_name

# 检查端口占用
netstat -tulpn | grep :2181
netstat -tulpn | grep :9876
```

#### 2. ZooKeeper连接失败

```bash
# 检查ZooKeeper服务状态
docker exec zookeeper zkServer.sh status

# 检查网络连接
docker exec consumer_gray ping zookeeper

# 查看ZooKeeper日志
docker-compose logs zookeeper
```

#### 3. RocketMQ连接失败

```bash
# 检查RocketMQ服务状态
docker-compose logs rocketmq

# 检查网络连接
docker exec consumer_gray ping rocketmq

# 验证RocketMQ端口
docker exec rocketmq netstat -tulpn | grep 9876
```

#### 4. Consumer服务异常

```bash
# 查看Consumer日志
docker-compose logs -f consumer_gray
docker-compose logs -f consumer_prd

# 检查环境变量
docker exec consumer_gray env | grep -E "NODE_TYPE|ROCKETMQ|ZOOKEEPER"

# 重启Consumer服务
docker-compose restart consumer_gray consumer_prd
```

### 调试模式

```bash
# 进入容器调试
docker exec -it consumer_gray /bin/bash
docker exec -it zookeeper /bin/bash

# 查看容器内部配置
docker exec consumer_gray cat /app/application.yml

# 查看Java进程
docker exec consumer_gray jps -l
```

## 性能监控

### 资源使用监控

```bash
# 查看容器资源使用情况
docker stats

# 查看特定容器资源使用
docker stats consumer_gray consumer_prd
```

### 应用监控

```bash
# 健康检查端点
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# 应用指标
curl http://localhost:8081/actuator/metrics
curl http://localhost:8082/actuator/metrics
```

## 数据持久化

### 数据卷管理

```bash
# 查看数据卷
docker volume ls

# 备份ZooKeeper数据
docker run --rm -v grayDemo2_zookeeper_data:/data -v $(pwd):/backup alpine tar czf /backup/zookeeper_backup.tar.gz -C /data .

# 恢复ZooKeeper数据
docker run --rm -v grayDemo2_zookeeper_data:/data -v $(pwd):/backup alpine tar xzf /backup/zookeeper_backup.tar.gz -C /data
```

### 数据清理

```bash
# 清理未使用的数据卷
docker volume prune

# 删除特定数据卷
docker volume rm grayDemo2_zookeeper_data grayDemo2_zookeeper_datalog
```

## 生产环境建议

### 1. 安全配置

- 配置ZooKeeper ACL权限
- 使用TLS加密通信
- 限制容器网络访问
- 定期更新镜像版本

### 2. 高可用配置

- ZooKeeper集群部署
- RocketMQ集群配置
- 负载均衡配置
- 健康检查和自动重启

### 3. 监控告警

- 集成Prometheus监控
- 配置Grafana仪表板
- 设置告警规则
- 日志聚合分析

### 4. 备份策略

- 定期备份ZooKeeper数据
- 配置文件版本控制
- 镜像版本管理
- 灾难恢复计划