#!/bin/bash

# Provider端灰度控制验证脚本

set -e

echo "=== Provider端灰度控制验证开始 ==="
echo

# 颜色输出函数
red() { echo -e "\033[31m$1\033[0m"; }
green() { echo -e "\033[32m$1\033[0m"; }
yellow() { echo -e "\033[33m$1\033[0m"; }

# 检查服务状态
check_service() {
    local service=$1
    local port=$2
    
    if nc -z localhost $port 2>/dev/null; then
        green "✅ $service (端口 $port) 运行正常"
        return 0
    else
        red "❌ $service (端口 $port) 未运行"
        return 1
    fi
}

# 检查Dubbo服务注册状态
check_dubbo_registry() {
    local service_name=$1
    local expected_port=$2
    local should_exist=$3
    
    # 获取Zookeeper容器ID
    ZK_CONTAINER=$(docker ps --filter "name=zookeeper" --format "{{.ID}}")
    if [ -z "$ZK_CONTAINER" ]; then
        red "❌ 无法找到Zookeeper容器"
        return 1
    fi
    
    # 检查服务注册信息
    registry_output=$(docker exec "$ZK_CONTAINER" zkCli.sh ls /dubbo/com.example.service.DubboDemoService/providers 2>/dev/null || echo "")
    
    # 解码URL并检查端口
    decoded_output=$(echo "$registry_output" | sed 's/%3A/:/g' | sed 's/%2F/\//g' | sed 's/%3F/?/g' | sed 's/%26/\&/g' | sed 's/%3D/=/g')
    
    # 检查是否包含指定的端口
    if echo "$decoded_output" | grep -q ":$expected_port"; then
        if [ "$should_exist" = "true" ]; then
            green "✅ $service_name Provider (端口: $expected_port) 已注册到Dubbo"
            return 0
        else
            red "❌ $service_name Provider (端口: $expected_port) 应该下线但仍在注册表中"
            return 1
        fi
    else
        if [ "$should_exist" = "false" ]; then
            green "✅ $service_name Provider (端口: $expected_port) 已从Dubbo注销"
            return 0
        else
            red "❌ $service_name Provider (端口: $expected_port) 应该上线但未在注册表中"
            return 1
        fi
    fi
}

# 设置发布状态
set_release_state() {
    local state=$1
    yellow "正在设置发布状态为: $state"
    
    response=$(curl -s -w "%{http_code}" -X POST "http://localhost:8081/api/release/state?stateName=$state" -o /dev/null)
    
    if [ "$response" = "200" ]; then
        green "✅ 状态设置成功"
        sleep 8  # 等待状态生效
    else
        red "❌ 状态设置失败 (HTTP $response)"
        return 1
    fi
}

# 验证状态切换
verify_state_switch() {
    local state=$1
    local gray_expected=$2
    local prd_expected=$3
    
    yellow "验证状态: $state"
    
    # 验证灰度provider (端口20881)
    if [ "$gray_expected" = "available" ]; then
        check_dubbo_registry "灰度" "20881" "true" || return 1
    else
        check_dubbo_registry "灰度" "20881" "false" || return 1
    fi
    
    # 验证生产provider (端口20882)
    if [ "$prd_expected" = "available" ]; then
        check_dubbo_registry "生产" "20882" "true" || return 1
    else
        check_dubbo_registry "生产" "20882" "false" || return 1
    fi
    
    green "✅ $state 状态验证通过"
    echo
}

# 主验证流程
main() {
    # 1. 检查基础服务
    yellow "1. 检查基础服务状态..."
    check_service "Zookeeper" 2181
    check_service "灰度Consumer" 8081
    check_service "生产Consumer" 8082
    echo
    
    # 2. 初始状态验证
    yellow "2. 验证初始状态 (ALL_ACCESSABLE)..."
    set_release_state "ALL_ACCESSABLE"
    verify_state_switch "ALL_ACCESSABLE" "available" "available"
    
    # 3. 灰度状态验证
    yellow "3. 验证GRAY_ACCESSABLE状态..."
    set_release_state "GRAY_ACCESSABLE"
    verify_state_switch "GRAY_ACCESSABLE" "available" "unavailable"
    
    # 4. 生产状态验证
    yellow "4. 验证PROD_ACCESSABLE状态..."
    set_release_state "PROD_ACCESSABLE"
    verify_state_switch "PROD_ACCESSABLE" "unavailable" "available"
    
    # 5. 恢复全量状态
    yellow "5. 恢复ALL_ACCESSABLE状态..."
    set_release_state "ALL_ACCESSABLE"
    verify_state_switch "ALL_ACCESSABLE" "available" "available"
    
    green "\n=== Provider端灰度控制验证完成 ✅ ==="
    echo
    green "总结: 所有状态切换验证通过，Provider端灰度控制工作正常"
}

# 检查curl命令
if ! command -v curl &> /dev/null; then
    red "错误: 需要安装curl"
    exit 1
fi

# 检查docker命令
if ! command -v docker &> /dev/null; then
    red "错误: 需要安装docker"
    exit 1
fi

# 运行主验证流程
main