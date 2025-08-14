package com.example.controller;

import com.example.enums.ReleaseState;
import com.example.service.ServiceAwareReleaseStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 服务感知的发布状态管理控制器
 * 支持每个微服务独立的灰度状态控制
 */
@RestController
@RequestMapping("/api/service-release")
public class ServiceAwareReleaseStateController {

    private static final Logger logger = LoggerFactory.getLogger(ServiceAwareReleaseStateController.class);

    @Autowired
    private ServiceAwareReleaseStateService releaseStateService;

    /**
     * 获取指定服务的当前发布状态
     */
    @GetMapping("/{serviceName}/state")
    public ResponseEntity<String> getServiceState(@PathVariable String serviceName) {
        try {
            ReleaseState currentState = releaseStateService.getServiceReleaseState(serviceName);
            return ResponseEntity.ok(currentState.getStateName());
        } catch (Exception e) {
            logger.error("Failed to get release state for service: {}", serviceName, e);
            return ResponseEntity.internalServerError().body("Failed to get service state");
        }
    }

    /**
     * 更新指定服务的发布状态
     */
    @PostMapping("/{serviceName}/state")
    public ResponseEntity<String> updateServiceState(
            @PathVariable String serviceName,
            @RequestParam String stateName) {
        try {
            ReleaseState newState = ReleaseState.fromStateName(stateName);
            releaseStateService.updateServiceReleaseState(serviceName, newState);
            logger.info("Release state updated for service {} to: {}", serviceName, newState.getStateName());
            return ResponseEntity.ok("Service " + serviceName + " state updated to: " + newState.getStateName());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid release state provided for service {}: {}", serviceName, stateName);
            return ResponseEntity.badRequest().body("Invalid state: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to update release state for service {} to: {}", serviceName, stateName, e);
            return ResponseEntity.internalServerError().body("Failed to update service state: " + e.getMessage());
        }
    }

    /**
     * 获取所有已注册的服务名
     */
    @GetMapping("/services")
    public ResponseEntity<Set<String>> getAllServices() {
        try {
            Set<String> services = releaseStateService.getAllServiceNames();
            return ResponseEntity.ok(services);
        } catch (Exception e) {
            logger.error("Failed to get all services", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取指定状态下各环境的消费规则
     */
    @GetMapping("/{serviceName}/state/{stateName}/rules")
    public ResponseEntity<String> getServiceStateRules(
            @PathVariable String serviceName,
            @PathVariable String stateName) {
        try {
            ReleaseState state = ReleaseState.fromStateName(stateName);
            boolean grayConsume = state.shouldConsume("GRAY_CONSUMER");
            boolean prodConsume = state.shouldConsume("PROD_CONSUMER");
            
            String rules = String.format("Service: %s, State: %s, Gray Environment: %s, Production Environment: %s",
                    serviceName,
                    state.getStateName(),
                    grayConsume ? "CONSUME" : "NOT_CONSUME",
                    prodConsume ? "CONSUME" : "NOT_CONSUME");
            
            return ResponseEntity.ok(rules);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid release state provided: {}", stateName);
            return ResponseEntity.badRequest().body("Invalid state: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to get state rules for service: {} state: {}", serviceName, stateName, e);
            return ResponseEntity.internalServerError().body("Failed to get state rules");
        }
    }

    /**
     * 获取所有服务的状态概览
     */
    @GetMapping("/overview")
    public ResponseEntity<String> getServicesOverview() {
        try {
            Set<String> services = releaseStateService.getAllServiceNames();
            StringBuilder overview = new StringBuilder();
            overview.append("Services Overview:\n");
            
            for (String service : services) {
                ReleaseState state = releaseStateService.getServiceReleaseState(service);
                overview.append(String.format("  %s: %s\n", service, state.getStateName()));
            }
            
            return ResponseEntity.ok(overview.toString());
        } catch (Exception e) {
            logger.error("Failed to get services overview", e);
            return ResponseEntity.internalServerError().body("Failed to get services overview");
        }
    }
}