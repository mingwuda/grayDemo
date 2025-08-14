# Provider端灰度控制验证方案

## 验证目标
验证Provider端通过Dubbo API控制服务注册/下线的灰度控制机制是否正常工作。

## 实现机制更新
从Zookeeper路径控制切换到了Dubbo原生API控制：
- 使用`ServiceConfig.export()`/`unexport()`API控制服务上下线
- 不再依赖Zookeeper的`/dubbo/service-status`路径
- 通过`ProviderServiceManager`监听`/release/status`状态变化，直接控制Dubbo服务生命周期

## 验证步骤

### 1. 环境准备
```bash
# 确保所有服务已启动
docker-compose up -d

# 等待服务完全启动
sleep 30
```

### 2. 初始状态验证

#### 2.1 检查服务注册状态
```bash
# 检查灰度provider（20881端口）是否注册
curl -s "http://localhost:8081/api/dubbo/test?consumerName=test-consumer"

# 检查生产provider（20882端口）是否注册
curl -s "http://localhost:8082/api/dubbo/test?consumerName=test-consumer"
```

#### 2.2 查看Zookeeper节点状态
```bash
# 进入Zookeeper容器
docker exec -it zookeeper /bin/bash

# 检查当前发布状态
zkCli.sh -server localhost:2181 get /release/status

# 查看Dubbo服务注册情况
zkCli.sh -server localhost:2181 ls /dubbo/com.example.service.DubboDemoService/providers
```

### 3. 状态切换测试

#### 3.1 切换到GRAY_ACCESSABLE状态
```bash
# 设置灰度发布状态
curl -X POST "http://localhost:8080/api/release/state?state=GRAY_ACCESSABLE"

# 等待状态生效（约5-10秒）
sleep 10

# 验证只有灰度provider可用
curl -s "http://localhost:8081/api/dubbo/test?consumerName=gray-test"

# 验证生产provider不可用（应该调用失败或超时）
curl -s "http://localhost:8082/api/dubbo/test?consumerName=prd-test"
```

#### 3.2 切换到PROD_ACCESSABLE状态
```bash
# 设置生产发布状态
curl -X POST "http://localhost:8080/api/release/state?state=PROD_ACCESSABLE"

# 等待状态生效
sleep 10

# 验证只有生产provider可用
curl -s "http://localhost:8082/api/dubbo/test?consumerName=prd-test"

# 验证灰度provider不可用
curl -s "http://localhost:8081/api/dubbo/test?consumerName=gray-test"
```

#### 3.3 切换到ALL_ACCESSABLE状态
```bash
# 设置全量发布状态
curl -X POST "http://localhost:8080/api/release/state?state=ALL_ACCESSABLE"

# 等待状态生效
sleep 10

# 验证所有provider都可用
curl -s "http://localhost:8081/api/dubbo/test?consumerName=test1"
curl -s "http://localhost:8082/api/dubbo/test?consumerName=test2"
```

### 4. 实时监控验证

#### 4.1 监控provider日志
```bash
# 监控灰度provider日志
docker-compose logs -f product_gray

# 监控生产provider日志
docker-compose logs -f product_prd
```

#### 4.2 检查服务注册/注销日志
在provider日志中应该能看到类似信息：
- 状态变为GRAY_ACCESSABLE时："Unregistering service: PRD"（生产环境）
- 状态变为PROD_ACCESSABLE时："Unregistering service: GRAY"（灰度环境）
- 状态变为ALL_ACCESSABLE时："Registering service..."（所有环境）

### 5. 自动化验证脚本

创建验证脚本：

```bash
#!/bin/bash
# test-provider-gray-control.sh

echo "=== Provider端灰度控制验证 ==="

# 测试函数
test_provider_availability() {
    local port=$1
    local expected_result=$2
    
    response=$(curl -s -w "%{http_code}" "http://localhost:$port/api/dubbo/test?consumerName=test" -o /dev/null)
    
    if [ "$response" = "200" ]; then
        if [ "$expected_result" = "available" ]; then
            echo "✅ 端口 $port 服务正常可用"
            return 0
        else
            echo "❌ 端口 $port 应该不可用但实际可用"
            return 1
        fi
    else
        if [ "$expected_result" = "unavailable" ]; then
            echo "✅ 端口 $port 已正确下线"
            return 0
        else
            echo "❌ 端口 $port 应该可用但无法访问"
            return 1
        fi
    fi
}

# 测试场景
echo "1. 测试GRAY_ACCESSABLE状态..."
curl -s -X POST "http://localhost:8081/api/release/state?stateName=GRAY_ACCESSABLE" > /dev/null
sleep 10
test_provider_availability 8081 "available"  # 灰度
test_provider_availability 8082 "unavailable"  # 生产

echo "2. 测试PROD_ACCESSABLE状态..."
curl -s -X POST "http://localhost:8081/api/release/state?stateName=PROD_ACCESSABLE" > /dev/null
sleep 10
test_provider_availability 8081 "unavailable"  # 灰度
test_provider_availability 8082 "available"  # 生产

echo "3. 测试ALL_ACCESSABLE状态..."
curl -s -X POST "http://localhost:8080/api/release/state?stateName=ALL_ACCESSABLE" > /dev/null
sleep 10
test_provider_availability 8081 "available"  # 灰度
test_provider_availability 8082 "available"  # 生产

echo "=== 验证完成 ==="
```

### 6. 预期结果

| 状态 | 灰度Provider(8081) | 生产Provider(8082) | 说明 |
|------|---------------------|---------------------|------|
| GRAY_ACCESSABLE | ✅ 可用 | ❌ 下线 | 仅灰度环境提供服务 |
| PROD_ACCESSABLE | ❌ 下线 | ✅ 可用 | 仅生产环境提供服务 |
| ALL_ACCESSABLE | ✅ 可用 | ✅ 可用 | 所有环境都提供服务 |

### 7. 故障排查

如果验证失败，检查以下方面：

1. **检查provider日志**
```bash
docker-compose logs product_gray | grep -i "register\|unregister\|export\|unexport"
docker-compose logs product_prd | grep -i "register\|unregister\|export\|unexport"
```

2. **检查Dubbo服务状态**
```bash
# 使用telnet测试Dubbo端口
telnet localhost 20881  # 灰度服务端口
telnet localhost 20882  # 生产服务端口
```

3. **检查Zookeeper状态监听**
```bash
docker exec -it zookeeper /bin/bash
zkCli.sh -server localhost:2181 get /release/status
```

4. **检查Dubbo注册中心**
```bash
# 查看Dubbo服务注册情况
docker exec -it zookeeper /bin/bash
zkCli.sh -server localhost:2181 ls /dubbo/com.example.service.DubboDemoService/providers
```

5. **检查ServiceConfig注入**
确保`ProviderServiceManager`中正确注入了`ServiceConfig`列表，可以通过查看启动日志确认。