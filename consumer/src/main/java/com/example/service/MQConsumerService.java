package com.example.service;

import com.example.enums.ReleaseState;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Service
public class MQConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(MQConsumerService.class);

    @Autowired
    private ServiceAwareReleaseStateService releaseStateService;
    
    @Value("${spring.application.name:default-service}")
    private String serviceName;

    @Value("${node.type}")
    private String nodeType;
    
    @Value("${rocketmq.consumer.group}")
    private String consumerGroup;
    
    @Value("${rocketmq.name-server}")
    private String nameServer;
    
    @Value("${rocketmq.consumer.topic}")
    private String topic;
    
    @Value("${rocketmq.consumer.tag}")
    private String tag;

    private DefaultMQPushConsumer consumer;
    private volatile boolean consumerStarted = false;

    @PostConstruct
    public void init() {
        // 注册状态变化监听器
        releaseStateService.addServiceStateChangeListener(serviceName, this::onReleaseStateChanged);
        
        // 根据当前状态决定是否启动消费者
        ReleaseState currentState = releaseStateService.getServiceReleaseState(serviceName);
        onReleaseStateChanged(currentState);
    }

    @PreDestroy
    public void destroy() {
        // 移除监听器
        releaseStateService.removeServiceStateChangeListener(serviceName, this::onReleaseStateChanged);
        shutdownConsumer();
    }

    /**
     * 处理发布状态变化
     */
    private void onReleaseStateChanged(ReleaseState newState) {
        boolean shouldConsume = newState.shouldConsume(nodeType);
        
        logger.info("Release state changed to: {}, node type: {}, should consume: {}", 
                newState.getStateName(), nodeType, shouldConsume);
        
        if (shouldConsume && !consumerStarted) {
            // 需要消费但消费者未启动，启动消费者
            try {
                startConsumer();
            } catch (MQClientException e) {
                logger.error("Failed to start consumer", e);
            }
        } else if (!shouldConsume && consumerStarted) {
            // 不需要消费但消费者已启动，停止消费者
            shutdownConsumer();
        }
        // 其他情况保持当前状态不变
    }

    private void startConsumer() throws MQClientException {
         if (consumerStarted) {
             return;
         }
         
         consumer = new DefaultMQPushConsumer();
         consumer.setConsumerGroup(consumerGroup);
         consumer.setNamesrvAddr(nameServer);
         consumer.subscribe(topic, tag);
        
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                String messageBody = new String(message.getBody());
                logger.info("[{}] Consumer] Received: {}", nodeType, messageBody);
                processMessage(messageBody);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        
        consumer.start();
        consumerStarted = true;
        logger.info("MQ Consumer started successfully");
    }
    
    private void shutdownConsumer() {
        if (consumer != null && consumerStarted) {
            consumer.shutdown();
            consumerStarted = false;
            logger.info("MQ Consumer shutdown successfully");
        }
    }

    private void processMessage(String message) {
        try {
            if ("GRAY_CONSUMER".equals(nodeType)) {
                logger.debug("Processing message in gray environment: {}", message);
            } else {
                logger.debug("Processing message in production environment: {}", message);
            }

            logger.info("Message processed successfully");
        } catch (Exception e) {
            logger.error("Error processing message", e);
            throw new RuntimeException("Message processing failed", e);
        }
    }
    
    /**
     * 检查消费者是否已启动
     * @return true表示消费者已启动，false表示未启动
     */
    public boolean isConsumerStarted() {
        return consumerStarted;
    }
}