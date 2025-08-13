package com.example.job;

import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.api.simple.SimpleJob;
import com.example.enums.ReleaseState;
import com.example.service.ReleaseStateService;
import com.example.util.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
public abstract class AbstractGrayControlledJob implements SimpleJob {

    private ReleaseStateService releaseStateService;

    private String nodeType = System.getenv("NODE_TYPE") != null ? System.getenv("NODE_TYPE") : "GRAY_CONSUMER";

    @Override
    public final void execute(ShardingContext shardingContext) {
        if (releaseStateService == null) {
            releaseStateService = SpringContextUtil.getBean(ReleaseStateService.class);
        }
        ReleaseState currentState = releaseStateService.getCurrentReleaseState();
        log.info("Current node type: {}, Release state: {}", nodeType, currentState);

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