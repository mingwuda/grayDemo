package com.example.service.impl;

import com.example.service.DubboDemoService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;

@DubboService(version = "1.0.0", group = "gray-demo", tag = "${node.type:GRAY}")
public class DubboDemoServiceImpl implements DubboDemoService {

    @Value("${node.type:GRAY_CONSUMER}")
    private String nodeType;

    @Override
    public String getServiceInfo(String consumerName) {
        return "Service called by " + consumerName + " from " + nodeType + " provider";
    }
}