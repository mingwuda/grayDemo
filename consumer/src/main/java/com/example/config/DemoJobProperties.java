package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "job.demo")
public class DemoJobProperties {
    private String cron;
    private int shardingTotalCount;
    private String shardingItemParameters;
}