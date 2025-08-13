package com.example.config;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ZooKeeper配置类
 * 负责创建和配置CuratorFramework客户端
 */
@Configuration
public class ZookeeperConfig {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperConfig.class);

    @Autowired
    private ZookeeperProperties zookeeperProperties;

    /**
     * 创建CuratorFramework Bean
     */
    @Bean(destroyMethod = "close")
    public CuratorFramework curatorFramework() {
        logger.info("Initializing ZooKeeper client with connect string: {}", 
                zookeeperProperties.getConnectString());
        
        // 创建重试策略：初始睡眠时间为1秒，最大重试次数为3次
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(1000, 3);
        
        // 创建CuratorFramework实例
        CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(zookeeperProperties.getConnectString())
                .sessionTimeoutMs(zookeeperProperties.getSessionTimeout())
                .connectionTimeoutMs(zookeeperProperties.getConnectionTimeout())
                .retryPolicy(retryPolicy)
                .build();
        
        // 启动客户端
        client.start();
        
        try {
            // 等待连接建立，最多等待30秒
            if (client.blockUntilConnected(30, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.info("Successfully connected to ZooKeeper");
            } else {
                logger.warn("Failed to connect to ZooKeeper within 30 seconds");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for ZooKeeper connection", e);
            Thread.currentThread().interrupt();
        }
        
        return client;
    }
}