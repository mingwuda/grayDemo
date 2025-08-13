package com.example.service;

import com.example.config.ZookeeperProperties;
import com.example.enums.ReleaseState;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 发布状态监听服务
 * 负责连接Zookeeper并监听发布状态节点的变化
 */
@Service
public class ReleaseStateService {

    private static final Logger logger = LoggerFactory.getLogger(ReleaseStateService.class);

    @Autowired
    private ZookeeperProperties zookeeperProperties;

    private CuratorFramework curatorFramework;
    private PathChildrenCache pathChildrenCache;
    private volatile ReleaseState currentReleaseState = ReleaseState.GRAY_ACCESSABLE;
    
    // 状态变化监听器列表
    private final CopyOnWriteArrayList<Consumer<ReleaseState>> stateChangeListeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        try {
            // 创建Curator客户端
            curatorFramework = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperProperties.getConnectString())
                    .sessionTimeoutMs(zookeeperProperties.getSessionTimeout())
                    .connectionTimeoutMs(zookeeperProperties.getConnectionTimeout())
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            
            curatorFramework.start();
            
            // 等待连接建立
            curatorFramework.blockUntilConnected();
            logger.info("Connected to Zookeeper: {}", zookeeperProperties.getConnectString());
            
            // 确保发布状态节点存在
            ensureReleaseStateNodeExists();
            
            // 初始化当前状态
            loadCurrentReleaseState();
            
            // 开始监听状态变化
            startWatching();
            
        } catch (Exception e) {
            logger.error("Failed to initialize Zookeeper connection", e);
            throw new RuntimeException("Failed to initialize Zookeeper connection", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (pathChildrenCache != null) {
                pathChildrenCache.close();
            }
            if (curatorFramework != null) {
                curatorFramework.close();
            }
            logger.info("Zookeeper connection closed");
        } catch (Exception e) {
            logger.error("Error closing Zookeeper connection", e);
        }
    }

    /**
     * 确保发布状态节点存在
     */
    private void ensureReleaseStateNodeExists() throws Exception {
        String path = zookeeperProperties.getReleaseStatePath();
        if (curatorFramework.checkExists().forPath(path) == null) {
            // 创建节点并设置默认状态
            curatorFramework.create()
                    .creatingParentsIfNeeded()
                    .forPath(path, ReleaseState.GRAY_ACCESSABLE.getStateName().getBytes());
            logger.info("Created release state node: {} with default state: {}", 
                    path, ReleaseState.GRAY_ACCESSABLE.getStateName());
        }
    }

    /**
     * 加载当前发布状态
     */
    private void loadCurrentReleaseState() throws Exception {
        String path = zookeeperProperties.getReleaseStatePath();
        byte[] data = curatorFramework.getData().forPath(path);
        if (data != null) {
            String stateName = new String(data);
            currentReleaseState = ReleaseState.fromStateName(stateName);
            logger.info("Loaded current release state: {}", currentReleaseState.getStateName());
        }
    }

    /**
     * 开始监听状态变化
     */
    private void startWatching() throws Exception {
        String parentPath = getParentPath(zookeeperProperties.getReleaseStatePath());
        
        pathChildrenCache = new PathChildrenCache(curatorFramework, parentPath, true);
        
        pathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                if (event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
                    String eventPath = event.getData().getPath();
                    if (eventPath.equals(zookeeperProperties.getReleaseStatePath())) {
                        byte[] data = event.getData().getData();
                        if (data != null) {
                            String newStateName = new String(data);
                            ReleaseState newState = ReleaseState.fromStateName(newStateName);
                            
                            if (newState != currentReleaseState) {
                                ReleaseState oldState = currentReleaseState;
                                currentReleaseState = newState;
                                logger.info("Release state changed from {} to {}", 
                                        oldState.getStateName(), newState.getStateName());
                                
                                // 通知所有监听器
                                notifyStateChangeListeners(newState);
                            }
                        }
                    }
                }
            }
        });
        
        pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        logger.info("Started watching release state changes on path: {}", 
                zookeeperProperties.getReleaseStatePath());
    }

    /**
     * 获取父路径
     */
    private String getParentPath(String fullPath) {
        int lastSlashIndex = fullPath.lastIndexOf('/');
        if (lastSlashIndex > 0) {
            return fullPath.substring(0, lastSlashIndex);
        }
        return "/";
    }

    /**
     * 通知状态变化监听器
     */
    private void notifyStateChangeListeners(ReleaseState newState) {
        for (Consumer<ReleaseState> listener : stateChangeListeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                logger.error("Error notifying state change listener", e);
            }
        }
    }

    /**
     * 添加状态变化监听器
     */
    public void addStateChangeListener(Consumer<ReleaseState> listener) {
        stateChangeListeners.add(listener);
    }

    /**
     * 移除状态变化监听器
     */
    public void removeStateChangeListener(Consumer<ReleaseState> listener) {
        stateChangeListeners.remove(listener);
    }

    /**
     * 获取当前发布状态
     */
    public ReleaseState getCurrentReleaseState() {
        return currentReleaseState;
    }

    /**
     * 更新发布状态到ZooKeeper
     */
    public void updateReleaseState(ReleaseState newState) throws Exception {
        if (curatorFramework == null) {
            throw new IllegalStateException("ZooKeeper client is not initialized");
        }
        
        String path = zookeeperProperties.getReleaseStatePath();
        byte[] data = newState.getStateName().getBytes();
        
        // 更新ZooKeeper节点数据
        curatorFramework.setData().forPath(path, data);
        
        logger.info("Updated release state to: {}", newState.getStateName());
    }
}