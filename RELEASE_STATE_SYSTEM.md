# 基于ZooKeeper的动态消费控制系统

## 系统概述

本系统实现了一个基于ZooKeeper的动态、精细化消费控制机制，能够根据发布状态动态控制灰度环境和生产环境的消息消费行为，无需重启服务即可实现消费者的启停控制。

## 系统架构

### 核心组件

1. **发布状态枚举 (ReleaseState)**
   - 定义了3种发布状态及其对应的消费规则
   - 每种状态明确规定灰度和生产环境是否消费消息

2. **ZooKeeper监听服务 (ReleaseStateService)**
   - 连接ZooKeeper并监听发布状态节点变化
   - 提供状态查询和更新功能
   - 通知消费服务状态变化

3. **消息消费服务 (MQConsumerService)**
   - 手动管理RocketMQ消费者生命周期
   - 根据节点类型和发布状态动态启停消费者
   - 监听状态变化事件

4. **状态管理控制器 (ReleaseStateController)**
   - 提供REST API进行状态查询和更新
   - 支持获取消费规则信息

## 发布状态定义

系统定义了3种发布状态，每种状态明确规定了灰度环境和生产环境消费者的消费行为：

| 状态枚举值 | 状态说明 | 灰度环境消费 | 生产环境消费 | 使用场景 |
|------------|----------|--------------|--------------|----------|
| GRAY_ACCESSABLE | 仅灰度可访问 | ✅ 消费 | ❌ 不消费 | 灰度验证阶段，仅灰度环境消费消息 |
| PROD_ACCESSABLE | 仅生产可访问 | ❌ 不消费 | ✅ 消费 | 生产验证阶段，仅生产环境消费消息 |
| ALL_ACCESSABLE | 全部可访问 | ✅ 消费 | ✅ 消费 | 发布完成，恢复所有环境正常消费 |

## 配置说明

### ZooKeeper配置 (application.yml)

```yaml
zookeeper:
  connect-string: localhost:2181
  session-timeout: 60000
  connection-timeout: 15000
  release-state-path: /gray-demo/release-state
```

### RocketMQ配置

```yaml
rocketmq:
  name-server: localhost:9876
  consumer:
    group: gray-demo-consumer-group
    topic: gray-demo-topic
    tag: "*"
    offset: CONSUME_FROM_LAST_OFFSET
    message-model: CLUSTERING
    consume-thread-max: 20
```

### 节点类型配置

```yaml
node:
  type: GRAY_CONSUMER  # 或 PROD_CONSUMER
```

## API接口

### 1. 获取当前发布状态
```bash
GET /api/release/state
```

### 2. 更新发布状态
```bash
POST /api/release/state?stateName=灰度发布中
```

### 3. 获取所有可用状态
```bash
GET /api/release/states
```

### 4. 获取状态消费规则
```bash
GET /api/release/state/{stateName}/rules
```

## 使用流程

### 1. 启动系统

#### 本地开发环境
1. 确保ZooKeeper服务运行在localhost:2181
2. 确保RocketMQ服务运行在localhost:9876
3. 启动consumer服务：
   ```bash
   cd consumer
   mvn spring-boot:run
   ```

#### Docker容器环境
1. 使用docker-compose启动所有服务：
   ```bash
   docker-compose up -d
   ```
2. 查看服务状态：
   ```bash
   docker-compose ps
   ```
3. 查看服务日志：
   ```bash
   docker-compose logs -f consumer_gray
   docker-compose logs -f consumer_prd
   ```

### 2. 灰度发布流程

1. **仅灰度可访问**
   ```bash
   curl -X POST "http://localhost:8081/api/release/state?stateName=GRAY_ACCESSABLE"
   ```
   - 灰度环境：消费消息 ✅
   - 生产环境：停止消费 ❌

2. **仅生产可访问**
   ```bash
   curl -X POST "http://localhost:8081/api/release/state?stateName=PROD_ACCESSABLE"
   ```
   - 灰度环境：停止消费 ❌
   - 生产环境：消费消息 ✅

3. **全部可访问**
   ```bash
   curl -X POST "http://localhost:8081/api/release/state?stateName=ALL_ACCESSABLE"
   ```
   - 灰度环境：消费消息 ✅
   - 生产环境：消费消息 ✅

## 测试脚本

### 本地环境测试
系统提供了完整的测试脚本 `test-release-state.sh`，可以自动测试所有状态转换：

```bash
./test-release-state.sh
```

### Docker环境测试
Docker环境提供了专门的测试脚本 `test-docker-release-state.sh`，测试容器化部署：

```bash
./test-docker-release-state.sh
```

#### Docker环境API端点
- **灰度Consumer API**: http://localhost:8081/api/release
- **生产Consumer API**: http://localhost:8082/api/release
- **ZooKeeper**: localhost:2181
- **RocketMQ**: localhost:9876

## 监控和日志

### 关键日志

- ZooKeeper连接状态
- 发布状态变化事件
- 消费者启停操作
- 配置更新事件

### 监控端点

- `/actuator/health` - 健康检查
- `/actuator/refresh` - 配置刷新（保留支持）
- `/api/release/state` - 当前发布状态

## 故障排查

### 常见问题

1. **ZooKeeper连接失败**
   - 检查ZooKeeper服务状态
   - 验证连接字符串配置
   - 检查网络连通性

2. **消费者启动失败**
   - 检查RocketMQ服务状态
   - 验证Topic和ConsumerGroup配置
   - 检查消费者权限

3. **状态更新不生效**
   - 检查ZooKeeper节点权限
   - 验证状态名称拼写
   - 查看服务日志

### 日志级别配置

```yaml
logging:
  level:
    com.example.service.ReleaseStateService: DEBUG
    com.example.service.MQConsumerService: DEBUG
    org.apache.curator: INFO
```

## 扩展功能

### 1. 多环境支持
- 可扩展支持更多环境类型
- 每个环境独立的消费规则

### 2. 状态持久化
- ZooKeeper自动提供状态持久化
- 服务重启后自动恢复状态

### 3. 集群支持
- 多个消费者实例共享同一状态
- 状态变化自动同步到所有实例

## 安全考虑

1. **ZooKeeper访问控制**
   - 配置适当的ACL权限
   - 使用安全的连接方式

2. **API接口安全**
   - 添加认证和授权机制
   - 限制状态更新权限

3. **配置安全**
   - 敏感配置加密存储
   - 定期轮换连接凭据