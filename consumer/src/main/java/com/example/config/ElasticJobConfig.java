package com.example.config;

import com.dangdang.ddframe.job.api.simple.SimpleJob;
import com.dangdang.ddframe.job.config.JobCoreConfiguration;
import com.dangdang.ddframe.job.config.simple.SimpleJobConfiguration;
import com.dangdang.ddframe.job.lite.api.JobScheduler;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.reg.zookeeper.ZookeeperConfiguration;
import com.dangdang.ddframe.job.reg.zookeeper.ZookeeperRegistryCenter;
import com.example.job.DemoJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticJobConfig {

    @Autowired
    private ZookeeperProperties zookeeperProperties;

    @Autowired
    private DemoJobProperties demoJobProperties;

    @Bean(initMethod = "init")
    public ZookeeperRegistryCenter regCenter() {
        return new ZookeeperRegistryCenter(new ZookeeperConfiguration(zookeeperProperties.getConnectString(), zookeeperProperties.getElasticJobNamespace()));
    }

    @Bean(initMethod = "init")
    public JobScheduler simpleJobScheduler(final DemoJob demoJob, final ZookeeperRegistryCenter regCenter) {
        return new JobScheduler(regCenter, createLiteJobConfiguration(demoJob.getClass(),
                demoJobProperties.getCron(),
                demoJobProperties.getShardingTotalCount(),
                demoJobProperties.getShardingItemParameters()));
    }

    private static LiteJobConfiguration createLiteJobConfiguration(final Class<? extends SimpleJob> jobClass,
                                                                 final String cron,
                                                                 final int shardingTotalCount,
                                                                 final String shardingItemParameters) {
        // 定义作业核心配置
        JobCoreConfiguration simpleCoreConfig = JobCoreConfiguration.newBuilder(jobClass.getName(), cron, shardingTotalCount).shardingItemParameters(shardingItemParameters).build();
        // 定义SIMPLE类型配置
        SimpleJobConfiguration simpleJobConfig = new SimpleJobConfiguration(simpleCoreConfig, jobClass.getCanonicalName());
        // 定义Lite作业根配置
        return LiteJobConfiguration.newBuilder(simpleJobConfig).overwrite(true).build();
    }
}
