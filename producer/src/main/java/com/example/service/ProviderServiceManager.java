package com.example.service;

import com.example.enums.ReleaseState;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private NodeCache releaseStateCache;
    private volatile boolean isServiceOnline = true;

    private final ReentrantLock statusLock = new ReentrantLock();

    @Autowired
    private List<ServiceConfig<?>> serviceConfigs;

    // 修复关键：记录每个服务的真实状态
    private final Map<String, ServiceStatus> serviceStatusMap = new ConcurrentHashMap<>();
    private final Set<String> registryCache = new HashSet<>();
    
    // 修复：确保协议端口一致
    @Value("${dubbo.protocol.port}")
    private int protocolPort;

    // 记录上次ZK节点数据，避免重复处理
    private volatile String lastZkState = "";

    private static class ServiceStatus {
        volatile boolean shouldBeRegistered;
        volatile boolean actuallyRegistered;
        
        ServiceStatus(boolean shouldBeRegistered) {
            this.shouldBeRegistered = shouldBeRegistered;
            this.actuallyRegistered = shouldBeRegistered;
        }
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing ProviderServiceManager for node: {}", nodeType);

            // 修复：清理ZK连接资源
            if (curatorFramework != null) {
                curatorFramework.close();
                curatorFramework = null;
            }

            // 1. 初始化Zookeeper连接
            curatorFramework = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperConnectString)
                    .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                    .build();
            curatorFramework.start();
            curatorFramework.blockUntilConnected();
            log.info("Successfully connected to ZooKeeper");

            // 2. 设置状态监听器
            setupReleaseStateListener();

            // 3. 初始状态检查（必须在监听器后执行）
            checkAndUpdateServiceStatus();

            log.info("ProviderServiceManager initialized successfully for node: {}", nodeType);
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
            log.info("Created initial release state node at: {}", releaseStatePath);
        }

        // 使用NodeCache精确监听单个节点
        releaseStateCache = new NodeCache(curatorFramework, releaseStatePath);
        releaseStateCache.getListenable().addListener(() -> {
            if (releaseStateCache.getCurrentData() != null && releaseStateCache.getCurrentData().getData() != null) {
                String newState = new String(releaseStateCache.getCurrentData().getData());
                log.info("Detected state change via ZK: {}", newState);
                
                // 修复：避免重复处理相同状态
                if (!newState.equals(lastZkState)) {
                    lastZkState = newState;
                    handleReleaseStateChange(ReleaseState.valueOf(newState));
                } else {
                    log.info("Skipping duplicate state change: {}", newState);
                }
            }
        });
        releaseStateCache.start(true); // 启动时获取当前数据
        log.info("ZK state listener activated at path: {}", releaseStatePath);
    }

    private void checkAndUpdateServiceStatus() {
        try {
            String releaseStatePath = "/release/status";
            byte[] data = curatorFramework.getData().forPath(releaseStatePath);
            if (data != null) {
                String currentState = new String(data);
                log.info("Initial state: {}, processing for node type: {}", currentState, nodeType);
                lastZkState = currentState;
                handleReleaseStateChange(ReleaseState.valueOf(currentState));
            } else {
                log.warn("No data in release state node, using default state");
                handleReleaseStateChange(ReleaseState.ALL_ACCESSABLE);
            }
        } catch (Exception e) {
            log.error("Failed to check release state", e);
            // 失败时保持安全状态
            log.warn("Defaulting to unregistered state due to error");
            safeUnregisterAllServices();
        }
    }

    private void handleReleaseStateChange(ReleaseState newState) {
        statusLock.lock();
        try {
            boolean shouldBeOnline = determineServiceStatus(newState);
            
            if (shouldBeOnline == isServiceOnline) {
                log.info("Service status unchanged ({}). Skipping operation.", 
                         shouldBeOnline ? "ONLINE" : "OFFLINE");
                return;
            }
            
            log.info("Processing state change: {} -> {} (nodeType={})", 
                     isServiceOnline ? "ONLINE" : "OFFLINE", 
                     shouldBeOnline ? "ONLINE" : "OFFLINE", 
                     nodeType);
            
            isServiceOnline = shouldBeOnline;
            
            if (shouldBeOnline) {
                registerService();
                log.info("Services set to ONLINE for node: {}", nodeType);
            } else {
                unregisterService();
                log.info("Services set to OFFLINE for node: {}", nodeType);
            }
        } finally {
            statusLock.unlock();
        }
    }

    private boolean determineServiceStatus(ReleaseState state) {
        boolean result = true;
        switch (state) {
            case GRAY_ACCESSABLE:
                result = "GRAY".equalsIgnoreCase(nodeType);
                break;
            case PROD_ACCESSABLE:
                result = "PRD".equalsIgnoreCase(nodeType);
                break;
            case ALL_ACCESSABLE:
                result = true;
                break;
            default:
                log.warn("Unknown state: {}, defaulting to accessible", state);
                result = true;
        }
        log.debug("State {} -> {} for node {}", state, result ? "ONLINE" : "OFFLINE", nodeType);
        return result;
    }

    private void registerService() {
        try {
            log.info("Registering services via Dubbo API...");
            startAllServices();
            log.info("Service registration complete");
        } catch (Exception e) {
            log.error("Failed to register services", e);
        }
    }

    private void unregisterService() {
        try {
            log.info("Unregistering services via Dubbo API...");
            stopAllServices();
            log.info("Service unregistration complete");
        } catch (Exception e) {
            log.error("Failed to unregister services", e);
        }
    }

    // 安全停止服务（异常处理更完善）
    private void safeUnregisterAllServices() {
        log.warn("Performing safe service shutdown");
        statusLock.lock();
        try {
            stopAllServices();
            isServiceOnline = false;
        } finally {
            statusLock.unlock();
        }
    }

    // 每30秒检查一次服务状态的一致性
    @Scheduled(fixedDelay = 30000)
    public void checkServiceStateConsistency() {
        statusLock.lock();
        try {
            log.debug("Running service state consistency check...");
            
            int inconsistencyCount = 0;
            for (ServiceConfig<?> service : serviceConfigs) {
                String serviceKey = service.getInterface();
                ServiceStatus status = serviceStatusMap.computeIfAbsent(
                    serviceKey, k -> new ServiceStatus(isServiceOnline)
                );
                
                boolean isActuallyRegistered = isRegisteredInZk(service);
                if (status.shouldBeRegistered != isActuallyRegistered) {
                    inconsistencyCount++;
                    log.warn("Inconsistency detected for service [{}]: should be registered={}, actually registered={}",
                             serviceKey, status.shouldBeRegistered, isActuallyRegistered);
                    
                    // 强制修正状态
                    if (status.shouldBeRegistered) {
                        reExportService(service);
                    } else {
                        if (service.isExported()) {
                            service.unexport();
                            log.info("Unexported service due to inconsistency: {}", serviceKey);
                        }
                    }
                }
            }
            
            if (inconsistencyCount > 0) {
                log.error("Fixed {} service inconsistencies", inconsistencyCount);
            } else {
                log.debug("No service state inconsistencies found");
            }
        } finally {
            statusLock.unlock();
        }
    }

    // 修复关键：重新导出服务的方法
    private void reExportService(ServiceConfig<?> service) {
        try {
            String serviceKey = service.getInterface();
            log.warn("Re-exporting service: {}", serviceKey);
            
            // 完全重新创建服务配置
            ServiceConfig newServiceConfig = new ServiceConfig<>();
            newServiceConfig.setInterface(service.getInterface());
            newServiceConfig.setRef(service.getRef());
            newServiceConfig.setVersion(service.getVersion());
            newServiceConfig.setGroup(service.getGroup());
            newServiceConfig.setProtocols(service.getProtocols());
            newServiceConfig.setRegistries(service.getRegistries());
            
            // 注销旧服务
            if (service.isExported()) {
                service.unexport();
            }
            
            // 重新导出
            newServiceConfig.export();
            
            // 替换服务列表中的实例
            serviceConfigs.remove(service);
            serviceConfigs.add(newServiceConfig);
            
            log.info("Successfully re-exported service: {}", serviceKey);
            registryCache.remove(serviceKey);
        } catch (Exception e) {
            log.error("Failed to re-export service: {}", service.getInterface(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down ProviderServiceManager...");
        try {
            if (releaseStateCache != null) {
                releaseStateCache.close();
                log.info("ZK state listener closed");
            }
            if (curatorFramework != null) {
                curatorFramework.close();
                log.info("ZK connection closed");
            }
            // 最后注销服务
            safeUnregisterAllServices();
        } catch (Exception e) {
            log.error("Error during cleanup", e);
        } finally {
            // 修复：清除所有服务状态
            registryCache.clear();
            serviceStatusMap.clear();
        }
    }

    public boolean isServiceOnline() {
        return isServiceOnline;
    }

    /**
     * 启动所有Dubbo服务（关键修复版）
     */
    private void startAllServices() {
        log.info("Starting services for node: {}", nodeType);
        int startedCount = 0;
        
        for (ServiceConfig<?> service : serviceConfigs) {
            String serviceKey = service.getInterface();
            ServiceStatus status = serviceStatusMap.computeIfAbsent(
                serviceKey, k -> new ServiceStatus(true)
            );
            status.shouldBeRegistered = true;
            
            try {
                // 关键修复：强制重新创建ServiceConfig来避免缓存问题
                log.info("重新创建并注册服务: {}", serviceKey);
                
                // 创建新的ServiceConfig实例
                ServiceConfig newService = new ServiceConfig<>();
                newService.setInterface(service.getInterface());
                newService.setRef(service.getRef());
                newService.setVersion(service.getVersion());
                newService.setGroup(service.getGroup());
                newService.setTimeout(service.getTimeout());
                newService.setProtocols(service.getProtocols());
                newService.setRegistries(service.getRegistries());
                
                // 注销旧服务
                if (service.isExported()) {
                    service.unexport();
                    log.info("已注销旧服务: {}", serviceKey);
                }
                
                // 导出新服务
                newService.export();
                log.info("已导出新服务: {}", serviceKey);
                
                // 更新服务列表
                serviceConfigs.remove(service);
                serviceConfigs.add(newService);
                
                startedCount++;
                status.actuallyRegistered = true;
                
                // 强制刷新缓存
                registryCache.clear();
                
            } catch (Exception e) {
                log.error("Failed to export service: {}", serviceKey, e);
                status.actuallyRegistered = false;
            }
        }
        
        if (startedCount > 0) {
            log.info("Successfully started {} Dubbo services", startedCount);
        } else {
            log.error("No services successfully started!");
        }
    }

    /**
     * 停止所有Dubbo服务（关键修复版）
     */
    private void stopAllServices() {
        log.info("Stopping services for node: {}", nodeType);
        int stoppedCount = 0;
        
        for (ServiceConfig<?> service : serviceConfigs) {
            String serviceKey = service.getInterface();
            ServiceStatus status = serviceStatusMap.computeIfAbsent(
                serviceKey, k -> new ServiceStatus(false)
            );
            status.shouldBeRegistered = false;
            
            try {
                if (service.isExported()) {
                    service.unexport();
                    stoppedCount++;
                    log.info("Unexported service: {}", serviceKey);
                    
                    // 验证是否从ZK注销
                    if (!isRegisteredInZk(service)) {
                        status.actuallyRegistered = false;
                        log.info("Successfully unregistered service: {}", serviceKey);
                    } else {
                        log.error("Service still registered in ZK after unexport: {}", serviceKey);
                        status.actuallyRegistered = true;
                    }
                } else {
                    log.debug("Service was not exported: {}", serviceKey);
                }
            } catch (Exception e) {
                log.error("Failed to unexport service: {}", serviceKey, e);
            }
        }
        
        if (stoppedCount > 0) {
            log.info("Successfully stopped {} Dubbo services", stoppedCount);
        } else {
            log.info("All services are already stopped");
        }
    }
    
    /**
     * 修复关键：检查服务是否实际在ZK注册
     */
    private boolean isRegisteredInZk(ServiceConfig<?> service) {
        try {
            String serviceKey = getServicePath(service);
            
            // 修复：使用缓存避免过多ZK查询
            if (registryCache.contains(serviceKey)) {
                return true;
            }
            
            // 实际检查ZK节点
            String zkPath = String.format("/dubbo/%s/providers", 
                    service.getInterface());
            List<String> providers = curatorFramework.getChildren().forPath(zkPath);
            
            boolean registered = providers.stream()
                .anyMatch(url -> url.contains(":" + protocolPort) && url.contains(service.getInterface()));
            
            if (registered) {
                registryCache.add(serviceKey);
            } else {
                registryCache.remove(serviceKey);
            }
            
            return registered;
        } catch (Exception e) {
            log.error("Failed to check registration status for service {}", service.getInterface(), e);
            return false;
        }
    }
    
    private String getServicePath(ServiceConfig<?> service) {
        return String.format("%s:%s:%d",
                service.getInterface(),
                service.getVersion(),
                protocolPort);
    }
}