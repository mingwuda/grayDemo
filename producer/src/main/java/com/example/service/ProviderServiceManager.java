package com.example.service;

import com.example.enums.ReleaseState;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Component
public class ProviderServiceManager {

    @Value("${node.type:PRD}")
    private String nodeType;
    
    @Autowired
    private ApplicationContext applicationContext;

    @Value("${zookeeper.connect-string:localhost:2181}")
    private String zookeeperConnectString;

    private CuratorFramework curatorFramework;
    private PathChildrenCache releaseStateCache;
    private volatile boolean isServiceOnline = true;

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

            // 监听发布状态变化
            setupReleaseStateListener();
            
            // 初始状态检查
            checkAndUpdateServiceStatus();
            
            log.info("ProviderServiceManager initialized for node: {}", nodeType);
        } catch (Exception e) {
            log.error("Failed to initialize ProviderServiceManager", e);
        }
    }

    private void setupReleaseStateListener() throws Exception {
        String releaseStatePath = "/release/status";
        
        // 确保路径存在
        if (curatorFramework.checkExists().forPath(releaseStatePath) == null) {
            curatorFramework.create().creatingParentsIfNeeded().forPath(releaseStatePath, 
                    ReleaseState.ALL_ACCESSABLE.name().getBytes());
        }

        releaseStateCache = new PathChildrenCache(curatorFramework, "/release", true);
        releaseStateCache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                if (event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED || 
                    event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
                    if ("status".equals(event.getData().getPath().substring("/release/".length()))) {
                        String newState = new String(event.getData().getData());
                        log.info("Release state changed to: {}", newState);
                        handleReleaseStateChange(ReleaseState.valueOf(newState));
                    }
                }
            }
        });
        
        releaseStateCache.start();
    }

    private void checkAndUpdateServiceStatus() {
        try {
            String releaseStatePath = "/release/status";
            if (curatorFramework.checkExists().forPath(releaseStatePath) != null) {
                byte[] data = curatorFramework.getData().forPath(releaseStatePath);
                String currentState = new String(data);
                handleReleaseStateChange(ReleaseState.valueOf(currentState));
            }
        } catch (Exception e) {
            log.error("Failed to check release state", e);
        }
    }

    private void handleReleaseStateChange(ReleaseState newState) {
        boolean shouldBeOnline = determineServiceStatus(newState);
        
        if (shouldBeOnline != isServiceOnline) {
            isServiceOnline = shouldBeOnline;
            
            if (shouldBeOnline) {
                registerService();
                log.info("Service {} is now ONLINE due to release state: {}", nodeType, newState);
            } else {
                unregisterService();
                log.info("Service {} is now OFFLINE due to release state: {}", nodeType, newState);
            }
        }
    }

    private boolean determineServiceStatus(ReleaseState state) {
        switch (state) {
            case GRAY_ACCESSABLE:
                return "GRAY".equalsIgnoreCase(nodeType);
            case PROD_ACCESSABLE:
                return "PRD".equalsIgnoreCase(nodeType);
            case ALL_ACCESSABLE:
                return true;
            default:
                return true;
        }
    }

    private void registerService() {
        try {
            log.info("Service {} is now ONLINE - enabling service registration", nodeType);
            
            // 通过创建/更新Zookeeper中的节点来控制服务注册
            String serviceStatusPath = "/dubbo/service-status/" + nodeType.toLowerCase();
            try {
                if (curatorFramework.checkExists().forPath(serviceStatusPath) == null) {
                    curatorFramework.create()
                            .creatingParentsIfNeeded()
                            .forPath(serviceStatusPath, "enabled".getBytes());
                } else {
                    curatorFramework.setData().forPath(serviceStatusPath, "enabled".getBytes());
                }
                log.info("Successfully enabled service registration for node: {}", nodeType);
            } catch (Exception e) {
                log.error("Failed to update service status in Zookeeper for node: {}", nodeType, e);
            }
            
        } catch (Exception e) {
            log.error("Failed to register service for node: {}", nodeType, e);
        }
    }

    private void unregisterService() {
        try {
            log.info("Service {} is now OFFLINE - disabling service registration", nodeType);
            
            // 通过删除Zookeeper中的节点来控制服务注销
            String serviceStatusPath = "/dubbo/service-status/" + nodeType.toLowerCase();
            try {
                if (curatorFramework.checkExists().forPath(serviceStatusPath) != null) {
                    curatorFramework.delete().forPath(serviceStatusPath);
                }
                log.info("Successfully disabled service registration for node: {}", nodeType);
            } catch (Exception e) {
                log.error("Failed to update service status in Zookeeper for node: {}", nodeType, e);
            }
            
        } catch (Exception e) {
            log.error("Failed to unregister service for node: {}", nodeType, e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (releaseStateCache != null) {
                releaseStateCache.close();
            }
            if (curatorFramework != null) {
                curatorFramework.close();
            }
        } catch (Exception e) {
            log.error("Error during ProviderServiceManager cleanup", e);
        }
    }

    public boolean isServiceOnline() {
        return isServiceOnline;
    }
}