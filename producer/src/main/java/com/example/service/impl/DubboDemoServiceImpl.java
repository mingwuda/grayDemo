package com.example.service.impl;

import com.example.service.DubboDemoService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.config.annotation.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@DubboService(version = "1.0.0", group = "gray-demo", 
              methods = {@Method(name = "getServiceInfo", timeout = 3000)})
@Slf4j
public class DubboDemoServiceImpl implements DubboDemoService {

    @Value("${node.type:PRD}")
    private String nodeType;

    @Override
    public String getServiceInfo(String consumerName) {
        log.info("Service called by {} from {} provider", consumerName, nodeType);
        return "Service called by " + consumerName + " from " + nodeType + " provider";
    }
}