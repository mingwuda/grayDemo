package com.example.job;

import com.dangdang.ddframe.job.api.ShardingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DemoJob extends AbstractGrayControlledJob {

    @Override
    protected void doExecute(ShardingContext shardingContext) {
        log.info("DemoJob is executing on sharding item: {}", shardingContext.getShardingItem());
        // Your job logic goes here.
    }
}