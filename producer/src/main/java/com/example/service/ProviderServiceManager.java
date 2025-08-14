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
import java.util.*;
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

    // 服务名称配置
    @Value("${spring.application.name:default-service}")
    private String serviceName;

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
        String releaseStatePath = "/release/" + serviceName + "/status";

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
                log.info("Detected state change for service {} via ZK: {}", serviceName, newState);
                
                // 修复：避免重复处理相同状态
                if (!newState.equals(lastZkState)) {
                    lastZkState = newState;
                    handleReleaseStateChange(ReleaseState.valueOf(newState));
                } else {
                    log.info("Skipping duplicate state change for service {}: {}", serviceName, newState);
                }
            }
        });
        releaseStateCache.start(true); // 启动时获取当前数据
        log.info("ZK state listener activated for service {} at path: {}", serviceName, releaseStatePath);
    }

    private void checkAndUpdateServiceStatus() {
        try {
            String releaseStatePath = "/release/" + serviceName + "/status";
            byte[] data = curatorFramework.getData().forPath(releaseStatePath);
            if (data != null) {
                String currentState = new String(data);
                log.info("Initial state for service {}: {}, processing for node type: {}", serviceName, currentState, nodeType);
                lastZkState = currentState;
                handleReleaseStateChange(ReleaseState.valueOf(currentState));
            } else {
                log.warn("No data in release state node for service {}, using default state", serviceName);
                handleReleaseStateChange(ReleaseState.ALL_ACCESSABLE);
            }
        } catch (Exception e) {
            log.error("Failed to check release state for service: {}", serviceName, e);
            // 失败时保持安全状态
            log.warn("Defaulting to unregistered state due to error for service: {}", serviceName);
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

    private void startAllServices() {
        log.info("Starting services for node: {}", nodeType);
        int startedCount = 0;

        // 修复：创建服务副本列表用于遍历
        List<ServiceConfig<?>> servicesToProcess = new ArrayList<>(serviceConfigs);

        for (ServiceConfig<?> service : servicesToProcess) {
            String serviceKey = service.getInterface();
            ServiceStatus status = serviceStatusMap.computeIfAbsent(
                    serviceKey, k -> new ServiceStatus(true)
            );
            status.shouldBeRegistered = true;

            try {
                // 修复：总是先尝试注销（确保刷新状态）
                if (service.isExported()) {
                    service.unexport();
                }

                // 重新导出服务
                service.export();

                // 检查注册情况
                if (isRegisteredInZk(service)) {
                    startedCount++;
                    status.actuallyRegistered = true;
                    log.info("Exported service: {} -> SUCCESS", serviceKey);
                } else {
                    log.error("Failed to register service in ZK: {}", serviceKey);
                    status.actuallyRegistered = false;

                    // 修复：需要重新导出的服务单独处理
                    if (shouldReexportOnFailure(service)) {
                        reExportService(service);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to export service: {}", serviceKey, e);
                status.actuallyRegistered = false;

                // 修复：需要重新导出的服务单独处理
                if (shouldReexportOnFailure(service)) {
                    reExportService(service);
                }
            }
        }

        if (startedCount > 0) {
            log.info("Successfully started {} Dubbo services", startedCount);
        } else {
            log.error("No services successfully started!");
        }
    }

    // 修复：避免在遍历过程中修改原始列表
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

            // 修复：使用线程安全的方式更新服务列表
            updateServiceConfig(service, newServiceConfig);

            log.info("Successfully re-exported service: {}", serviceKey);
            registryCache.remove(serviceKey);
        } catch (Exception e) {
            log.error("Failed to re-export service: {}", service.getInterface(), e);
        }
    }

    // 修复：线程安全地更新服务配置
    private void updateServiceConfig(ServiceConfig<?> oldService, ServiceConfig<?> newService) {
        statusLock.lock();
        try {
            // 从列表中移除旧服务配置
            serviceConfigs.remove(oldService);

            // 添加新服务配置
            serviceConfigs.add(newService);

            // 更新状态映射
            ServiceStatus status = serviceStatusMap.get(oldService.getInterface());
            if (status != null) {
                serviceStatusMap.put(newService.getInterface(), status);
            }
        } finally {
            statusLock.unlock();
        }
    }

    // 判断是否需要重新导出
    private boolean shouldReexportOnFailure(ServiceConfig<?> service) {
        // 根据实际情况添加判断条件
        return true;
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