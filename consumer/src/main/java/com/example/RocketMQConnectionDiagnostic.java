package com.example;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.protocol.body.ConsumerConnection;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class RocketMQConnectionDiagnostic {

    private static final Logger logger = LoggerFactory.getLogger(RocketMQConnectionDiagnostic.class);

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.consumer.group}")
    private String consumerGroup;

    @PostConstruct
    public void diagnoseConnection() {
        logger.info("Starting RocketMQ connection diagnosis...");
        logger.info("NameServer address: {}", nameServer);
        logger.info("Consumer group: {}", consumerGroup);

        testNameServerConnection();
        testBrokerConnection();
        testConsumerRegistration();
    }

    private void testNameServerConnection() {
        logger.info("Testing NameServer connection...");
        try {
            DefaultMQAdminExt admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(nameServer);
            admin.start();

            // 获取集群信息
            Map<String, BrokerData> clusterInfo = admin.examineBrokerClusterInfo().getBrokerAddrTable();
            logger.info("Successfully connected to NameServer. Found {} brokers", clusterInfo.size());

            admin.shutdown();
        } catch (Exception e) {
            logger.error("Failed to connect to NameServer: {}", nameServer, e);
            logger.error("Possible causes:");
            logger.error("1. NameServer not running at {}", nameServer);
            logger.error("2. Network connectivity issue");
            logger.error("3. Firewall blocking port 9876");
        }
    }

    private void testBrokerConnection() {
        logger.info("Testing Broker connection...");
        try {
            // 创建临时生产者测试连接
            DefaultMQProducer producer = new DefaultMQProducer("DiagnosticProducerGroup");
            producer.setNamesrvAddr(nameServer);
            producer.start();

            logger.info("Successfully connected to Broker via NameServer");
            producer.shutdown();
        } catch (MQClientException e) {
            logger.error("Failed to connect to Broker: {}", e.getMessage());
            logger.error("Possible causes:");
            logger.error("1. Broker not registered with NameServer");
            logger.error("2. Network issue between consumer and Broker");
            logger.error("3. Firewall blocking port 10911");
        }
    }

    private void testConsumerRegistration() {
        logger.info("Testing consumer registration...");
        try {
            DefaultMQAdminExt admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(nameServer);
            admin.start();

            // 尝试获取消费者组信息
            ConsumerConnection consumerConnection = admin.examineConsumerConnectionInfo(consumerGroup);
            logger.info("Consumer group {} is registered", consumerGroup);

            admin.shutdown();
        } catch (Exception e) {
            logger.error("Failed to register consumer group: {}", consumerGroup, e);
            logger.error("Possible causes:");
            logger.error("1. Consumer group name invalid: {}", consumerGroup);
            logger.error("2. Broker configuration issue");
        }
    }
}