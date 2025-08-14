package com.example.job;

import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.api.simple.SimpleJob;
import com.example.enums.ReleaseState;
import com.example.service.ServiceAwareReleaseStateService;
import com.example.util.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractGrayControlledJob implements SimpleJob {

    private ServiceAwareReleaseStateService releaseStateService;

    private String nodeType = System.getenv("NODE_TYPE") != null ? System.getenv("NODE_TYPE") : "GRAY_CONSUMER";

    private String serviceName = System.getenv("SERVICE_NAME") != null ? System.getenv("SERVICE_NAME") : "default-service";

    @Override
    public final void execute(ShardingContext shardingContext) {
        if (releaseStateService == null) {
            releaseStateService = SpringContextUtil.getBean(ServiceAwareReleaseStateService.class);
        }
        ReleaseState currentState = releaseStateService.getServiceReleaseState(serviceName);
        log.info("Current service: {}, node type: {}, release state: {}", serviceName, nodeType, currentState);

        boolean isGrayNode = nodeType != null && nodeType.toUpperCase().contains("GRAY");

        switch (currentState) {
            case GRAY_ACCESSABLE:
                if (isGrayNode) {
                    log.info("Executing job on GRAY node (state: GRAY_ACCESSABLE). Sharding: {}", shardingContext.getShardingItem());
                    doExecute(shardingContext);
                } else {
                    log.info("Skipping job on PRD node (state: GRAY_ACCESSABLE).");
                }
                break;
            case PROD_ACCESSABLE:
                if (!isGrayNode) {
                    log.info("Executing job on PRD node (state: PROD_ACCESSABLE). Sharding: {}", shardingContext.getShardingItem());
                    doExecute(shardingContext);
                } else {
                    log.info("Skipping job on GRAY node (state: PROD_ACCESSABLE).");
                }
                break;
            case ALL_ACCESSABLE:
                log.info("Executing job on {} node (state: ALL_ACCESSABLE). Sharding: {}", nodeType, shardingContext.getShardingItem());
                doExecute(shardingContext);
                break;
        }
    }

    protected abstract void doExecute(ShardingContext shardingContext);
}