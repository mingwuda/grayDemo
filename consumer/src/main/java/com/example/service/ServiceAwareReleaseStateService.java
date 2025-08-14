package com.example.service;


import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.NodeCache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.config.ZookeeperProperties;
import com.example.enums.ReleaseState;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class ServiceAwareReleaseStateService {
    private static final Logger logger = LoggerFactory.getLogger(ServiceAwareReleaseStateService.class);

    @Autowired
    private CuratorFramework curatorFramework;

    @Autowired
    private ZookeeperProperties zookeeperProperties;

    // 服务特定的状态缓存
    private final ConcurrentHashMap<String, ReleaseState> serviceStates = new ConcurrentHashMap<>();
    
    // 服务特定的监听器缓存
    private final ConcurrentHashMap<String, NodeCache> serviceCaches = new ConcurrentHashMap<>();
    
    // 服务特定的状态变化监听器
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<ReleaseState>>> serviceStateChangeListeners = new ConcurrentHashMap<>();

    /**
     * 获取指定服务的发布状态
     */
    public ReleaseState getServiceReleaseState(String serviceName) {
        return serviceStates.getOrDefault(serviceName, ReleaseState.ALL_ACCESSABLE);
    }

    /**
     * 更新指定服务的发布状态
     */
    public void updateServiceReleaseState(String serviceName, ReleaseState state) {
        try {
            String path = zookeeperProperties.getReleaseStatePath() + "/" + serviceName + "/status";
            String stateStr = state.name();
            
            if (curatorFramework.checkExists().forPath(path) == null) {
                curatorFramework.create().creatingParentsIfNeeded().forPath(path, stateStr.getBytes());
            } else {
                curatorFramework.setData().forPath(path, stateStr.getBytes());
            }
            
            serviceStates.put(serviceName, state);
            logger.info("Updated release state for service {} to {}", serviceName, state);
        } catch (Exception e) {
            logger.error("Failed to update release state for service {} to {}", serviceName, state, e);
        }
    }

    /**
     * 获取所有已注册的服务名称
     */
    public Set<String> getAllServiceNames() {
        try {
            String path = zookeeperProperties.getReleaseStatePath();
            if (curatorFramework.checkExists().forPath(path) != null) {
                List<String> children = curatorFramework.getChildren().forPath(path);
                return new HashSet<>(children);
            }
        } catch (Exception e) {
            logger.error("Failed to get service names", e);
        }
        return Collections.emptySet();
    }

    /**
     * 监听指定服务的发布状态变化
     */
    public void watchServiceState(String serviceName, Consumer<ReleaseState> listener) {
        try {
            String path = zookeeperProperties.getReleaseStatePath() + "/" + serviceName + "/status";
            
            // 使用NodeCache监听具体节点的数据变化
            NodeCache cache = new NodeCache(curatorFramework, path);
            cache.getListenable().addListener(() -> {
                if (cache.getCurrentData() != null && cache.getCurrentData().getData() != null) {
                    byte[] data = cache.getCurrentData().getData();
                    String stateStr = new String(data);
                    ReleaseState newState = ReleaseState.valueOf(stateStr);
                    
                    serviceStates.put(serviceName, newState);
                    listener.accept(newState);
                    
                    logger.info("Detected state change for service {}: {}", serviceName, newState);
                }
            });
            
            cache.start(true);
            serviceCaches.put(serviceName, cache);
            
            // 初始化当前状态
            if (curatorFramework.checkExists().forPath(path) != null) {
                byte[] data = curatorFramework.getData().forPath(path);
                String stateStr = new String(data);
                ReleaseState currentState = ReleaseState.valueOf(stateStr);
                serviceStates.put(serviceName, currentState);
            }
            
        } catch (Exception e) {
            logger.error("Failed to watch service state for service {}", serviceName, e);
        }
    }

    /**
     * 停止监听指定服务的发布状态变化
     */
    public void unwatchServiceState(String serviceName) {
        NodeCache cache = serviceCaches.remove(serviceName);
        if (cache != null) {
            try {
                cache.close();
            } catch (IOException e) {
                logger.error("Failed to close cache for service {}", serviceName, e);
            }
        }
    }

    /**
     * 添加服务状态变化监听器
     */
    public void addServiceStateChangeListener(String serviceName, Consumer<ReleaseState> listener) {
        serviceStateChangeListeners.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>()).add(listener);
        watchServiceState(serviceName, newState -> {
            CopyOnWriteArrayList<Consumer<ReleaseState>> listeners = serviceStateChangeListeners.get(serviceName);
            if (listeners != null) {
                for (Consumer<ReleaseState> l : listeners) {
                    try {
                        l.accept(newState);
                    } catch (Exception e) {
                        logger.error("Error notifying service state change listener for service: {}", serviceName, e);
                    }
                }
            }
        });
    }

    /**
     * 移除服务状态变化监听器
     */
    public void removeServiceStateChangeListener(String serviceName, Consumer<ReleaseState> listener) {
        CopyOnWriteArrayList<Consumer<ReleaseState>> listeners = serviceStateChangeListeners.get(serviceName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                serviceStateChangeListeners.remove(serviceName);
                unwatchServiceState(serviceName);
            }
        }
    }
}