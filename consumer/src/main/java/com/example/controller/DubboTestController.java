package com.example.controller;

import com.example.service.DubboConsumerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/dubbo")
public class DubboTestController {

    @Autowired
    private DubboConsumerService dubboConsumerService;

    @GetMapping("/test")
    public ResponseEntity<String> testDubboCall(@RequestParam(required = false, defaultValue = "consumer") String consumerName) {
        try {
            String result = dubboConsumerService.callProviderService(consumerName);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error in dubbo test", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}