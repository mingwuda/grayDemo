package com.example.controller;

import com.example.service.MQConsumerService;
import com.example.service.ReleaseStateService;
import com.example.enums.ReleaseState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Autowired
    private MQConsumerService mqConsumerService;
    
    @Autowired
    private ReleaseStateService releaseStateService;
    
    @Value("${node.type:GRAY_CONSUMER}")
    private String nodeType;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            // 获取当前发布状态
            ReleaseState currentState = releaseStateService.getCurrentReleaseState();
            boolean shouldConsume = currentState.shouldConsume(nodeType);
            boolean isConsumerStarted = mqConsumerService.isConsumerStarted();
            
            status.put("nodeType", nodeType);
            status.put("currentReleaseState", currentState.getStateName());
            status.put("shouldConsume", shouldConsume);
            status.put("consumerStarted", isConsumerStarted);
            
            // 健康状态判断：如果应该消费且消费者已启动，或者不应该消费且消费者未启动，则为健康
            if ((shouldConsume && isConsumerStarted) || (!shouldConsume && !isConsumerStarted)) {
                status.put("status", "UP");
                status.put("reason", "Consumer state matches expected behavior");
                return ResponseEntity.ok(status);
            } else {
                status.put("status", "DOWN");
                if (shouldConsume && !isConsumerStarted) {
                    status.put("reason", "Consumer should be running but is not started");
                } else {
                    status.put("reason", "Consumer should not be running but is started");
                }
                return ResponseEntity.status(503).body(status);
            }
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("reason", "Health check failed: " + e.getMessage());
            return ResponseEntity.status(503).body(status);
        }
    }
}