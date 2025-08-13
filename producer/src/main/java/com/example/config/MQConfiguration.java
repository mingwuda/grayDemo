package com.example.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MQConfiguration {

    @Value("${node.type}")
    private String nodeType;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${app.topic}")
    private String topic;

    @Value("${app.tags.production}")
    private String productionTag;

    @Value("${app.tags.gray}")
    private String grayTag;

    @Bean
    public DefaultMQProducer mqProducer() {
        String producerGroup = "GRAY".equals(nodeType)
                ? "GRAY_PRODUCER_GROUP" : "PRD_PRODUCER_GROUP";

        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);

        // 重要：不在这里调用start()！
        return producer;
    }

    @Bean
    public RocketMQTemplate rocketMQTemplate(DefaultMQProducer mqProducer) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(mqProducer);  // 注入生产者但不启动


        return template;
    }

    public String getTopic() {
        return topic;
    }

    public String getTargetTag() {
        return "GRAY".equals(nodeType) ? grayTag : productionTag;
    }
}