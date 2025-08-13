package com.example.controller;

import com.example.enums.ReleaseState;
import com.example.service.ReleaseStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 发布状态管理控制器
 * 提供REST接口用于查询和更新发布状态
 */
@RestController
@RequestMapping("/api/release")
public class ReleaseStateController {

    private static final Logger logger = LoggerFactory.getLogger(ReleaseStateController.class);

    @Autowired
    private ReleaseStateService releaseStateService;

    /**
     * 获取当前发布状态
     */
    @GetMapping("/state")
    public ResponseEntity<String> getCurrentState() {
        try {
            ReleaseState currentState = releaseStateService.getCurrentReleaseState();
            return ResponseEntity.ok(currentState.getStateName());
        } catch (Exception e) {
            logger.error("Failed to get current release state", e);
            return ResponseEntity.internalServerError().body("Failed to get current state");
        }
    }

    /**
     * 更新发布状态
     */
    @PostMapping("/state")
    public ResponseEntity<String> updateState(@RequestParam String stateName) {
        try {
            ReleaseState newState = ReleaseState.fromStateName(stateName);
            releaseStateService.updateReleaseState(newState);
            logger.info("Release state updated to: {}", newState.getStateName());
            return ResponseEntity.ok("State updated successfully to: " + newState.getStateName());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid release state provided: {}", stateName);
            return ResponseEntity.badRequest().body("Invalid state: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to update release state to: {}", stateName, e);
            return ResponseEntity.internalServerError().body("Failed to update state: " + e.getMessage());
        }
    }

    /**
     * 获取所有可用的发布状态
     */
    @GetMapping("/states")
    public ResponseEntity<String[]> getAllStates() {
        try {
            ReleaseState[] states = ReleaseState.values();
            String[] stateNames = new String[states.length];
            for (int i = 0; i < states.length; i++) {
                stateNames[i] = states[i].getStateName();
            }
            return ResponseEntity.ok(stateNames);
        } catch (Exception e) {
            logger.error("Failed to get all states", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取指定状态下各环境的消费规则
     */
    @GetMapping("/state/{stateName}/rules")
    public ResponseEntity<String> getStateRules(@PathVariable String stateName) {
        try {
            ReleaseState state = ReleaseState.fromStateName(stateName);
            boolean grayConsume = state.shouldConsume("GRAY_CONSUMER");
            boolean prodConsume = state.shouldConsume("PROD_CONSUMER");
            
            String rules = String.format("State: %s, Gray Environment: %s, Production Environment: %s",
                    state.getStateName(),
                    grayConsume ? "CONSUME" : "NOT_CONSUME",
                    prodConsume ? "CONSUME" : "NOT_CONSUME");
            
            return ResponseEntity.ok(rules);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid release state provided: {}", stateName);
            return ResponseEntity.badRequest().body("Invalid state: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to get state rules for: {}", stateName, e);
            return ResponseEntity.internalServerError().body("Failed to get state rules");
        }
    }
}