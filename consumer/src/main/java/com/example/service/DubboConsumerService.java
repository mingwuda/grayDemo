package com.example.service;

import com.example.enums.ReleaseState;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

@Slf4j
@Service
public class DubboConsumerService {

    @DubboReference(version = "1.0.0", group = "gray-demo", check = false, parameters = {"dubbo.tag.force=false"})
    private DubboDemoService dubboDemoService;

    @Autowired
    private ReleaseStateService releaseStateService;

    public String callProviderService(HttpServletRequest request, String consumerName) {
        try {
            ReleaseState currentState = releaseStateService.getCurrentReleaseState();
            String grayFlag = getGrayFlagFromCookie(request);

            // 优先根据发布状态进行路由
            if (currentState == ReleaseState.GRAY_ACCESSABLE) {
                RpcContext.getContext().setAttachment("dubbo.tag", "GRAY");
                log.info("Release state is GRAY_ACCESSABLE, forcing route to GRAY provider for consumer: {}", consumerName);
            } else if (currentState == ReleaseState.PROD_ACCESSABLE) {
                RpcContext.getContext().setAttachment("dubbo.tag", "PRD");
                log.info("Release state is PROD_ACCESSABLE, forcing route to PRD provider for consumer: {}", consumerName);
            } else {
                // ALL_ACCESSABLE 状态下，根据cookie进行路由
                if ("gray".equalsIgnoreCase(grayFlag)) {
                    RpcContext.getContext().setAttachment("dubbo.tag", "GRAY");
                    log.info("Routing to GRAY provider for consumer: {} based on cookie", consumerName);
                } else if ("prd".equalsIgnoreCase(grayFlag)) {
                    RpcContext.getContext().setAttachment("dubbo.tag", "PRD");
                    log.info("Routing to PRD provider for consumer: {} based on cookie", consumerName);
                } else {
                    // 当没有cookie时，随机选择一个标签进行路由
                    if (new java.util.Random().nextBoolean()) {
                        RpcContext.getContext().setAttachment("dubbo.tag", "GRAY");
                        log.info("No GrayFlag cookie, randomly routing to GRAY provider for consumer: {}", consumerName);
                    } else {
                        RpcContext.getContext().setAttachment("dubbo.tag", "PRD");
                        log.info("No GrayFlag cookie, randomly routing to PRD provider for consumer: {}", consumerName);
                    }
                }
            }
            
            return dubboDemoService.getServiceInfo(consumerName);
        } catch (Exception e) {
            log.error("Error calling dubbo service", e);
            return "Error calling provider service: " + e.getMessage();
        }
    }

    private String getGrayFlagFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("GrayFlag".equalsIgnoreCase(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}