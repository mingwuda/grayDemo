package com.example.controller;

import com.example.config.MQConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProducerController {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private MQConfiguration mqConfiguration;

    @GetMapping("/send")
    public String sendMessage() {
        String message = "Message at " + System.currentTimeMillis();
        String topic = mqConfiguration.getTopic();
        String tag = mqConfiguration.getTargetTag();
        String destination = topic + ":" + tag;

        rocketMQTemplate.convertAndSend(destination, message);
        return "Sent to " + destination + ": " + message;
    }
}