package com.example.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DubboConsumerService {

    @DubboReference(version = "1.0.0", group = "gray-demo", check = false, tag = "*")
    private DubboDemoService dubboDemoService;

    public String callProviderService(String consumerName) {
        try {
            return dubboDemoService.getServiceInfo(consumerName);
        } catch (Exception e) {
            log.error("Error calling dubbo service", e);
            return "Error calling provider service: " + e.getMessage();
        }
    }
}