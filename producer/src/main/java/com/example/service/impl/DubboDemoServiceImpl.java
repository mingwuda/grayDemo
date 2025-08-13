package com.example.service.impl;

import com.example.service.DubboDemoService;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.config.annotation.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
@DubboService(version = "1.0.0", group = "gray-demo", 
              methods = {@Method(name = "getServiceInfo", timeout = 3000)})
@Slf4j
public class DubboDemoServiceImpl implements DubboDemoService {

    @Value("${node.type:PRD}")
    private String nodeType;

    @Value("${zookeeper.connect-string:localhost:2181}")
    private String zookeeperConnectString;

    private CuratorFramework curatorFramework;
    private PathChildrenCache serviceStatusCache;
    private volatile boolean isServiceEnabled = true;

    @PostConstruct
    public void init() {
        try {
            // 初始化Zookeeper连接
            curatorFramework = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperConnectString)
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            curatorFramework.start();
            curatorFramework.blockUntilConnected();

            // 监听服务状态变化
            setupServiceStatusListener();
            
            // 初始状态检查
            checkServiceStatus();
            
            log.info("DubboDemoServiceImpl initialized for node: {} with service enabled: {}", 
                     nodeType, isServiceEnabled);
        } catch (Exception e) {
            log.error("Failed to initialize DubboDemoServiceImpl", e);
            isServiceEnabled = true; // 默认启用
        }
    }

    private void setupServiceStatusListener() throws Exception {
        String serviceStatusPath = "/dubbo/service-status";
        
        // 确保路径存在
        if (curatorFramework.checkExists().forPath(serviceStatusPath) == null) {
            curatorFramework.create().creatingParentsIfNeeded().forPath(serviceStatusPath);
        }

        serviceStatusCache = new PathChildrenCache(curatorFramework, serviceStatusPath, true);
        serviceStatusCache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED || 
                    event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED ||
                    event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
                    checkServiceStatus();
                }
            }
        });
        
        serviceStatusCache.start();
    }

    private void checkServiceStatus() {
        try {
            String serviceStatusPath = "/dubbo/service-status/" + nodeType.toLowerCase();
            if (curatorFramework.checkExists().forPath(serviceStatusPath) != null) {
                byte[] data = curatorFramework.getData().forPath(serviceStatusPath);
                String status = new String(data);
                isServiceEnabled = "enabled".equals(status);
                log.info("Service status updated for node {}: enabled={}", nodeType, isServiceEnabled);
            } else {
                isServiceEnabled = true; // 默认启用
                log.info("No service status found for node {}, defaulting to enabled", nodeType);
            }
        } catch (Exception e) {
            log.error("Failed to check service status", e);
            isServiceEnabled = true; // 出错时默认启用
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (serviceStatusCache != null) {
                serviceStatusCache.close();
            }
            if (curatorFramework != null) {
                curatorFramework.close();
            }
        } catch (Exception e) {
            log.error("Error during DubboDemoServiceImpl cleanup", e);
        }
    }

    @Override
    public String getServiceInfo(String consumerName) {
        if (!isServiceEnabled) {
            log.warn("Service called by {} but service is disabled for node {}", consumerName, nodeType);
            throw new RuntimeException("Service temporarily unavailable for node: " + nodeType);
        }
        
        log.info("Service called by {} from {} provider", consumerName, nodeType);
        return "Service called by " + consumerName + " from " + nodeType + " provider";
    }
}