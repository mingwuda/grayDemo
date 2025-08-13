package com.example.service;

import com.example.config.ZookeeperProperties;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
public class DubboRoutingRuleService {

    @Autowired
    private ZookeeperProperties zookeeperProperties;

    private CuratorFramework curatorFramework;

    @PostConstruct
    public void init() throws Exception {
        // 创建Zookeeper连接
        curatorFramework = CuratorFrameworkFactory.builder()
                .connectString(zookeeperProperties.getConnectString())
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        curatorFramework.start();
        curatorFramework.blockUntilConnected();

        // 不再自动设置Dubbo标签路由规则
        // setDubboTagRoutingRule();
    }

    private void setDubboTagRoutingRule() throws Exception {
        String path = "/dubbo/config/dubbo/rocketmq-gray-producer.tag-router";
        String rule = "---\n" +
                "force: true\n" +
                "runtime: true\n" +
                "enabled: true\n" +
                "priority: 1\n" +
                "tags:\n" +
                "  - name: GRAY\n" +
                "    addrs: []\n" +
                "  - name: PRD\n" +
                "    addrs: []\n";

        // 创建或更新路由规则节点
        if (curatorFramework.checkExists().forPath(path) == null) {
            curatorFramework.create().creatingParentsIfNeeded().forPath(path, rule.getBytes());
        } else {
            curatorFramework.setData().forPath(path, rule.getBytes());
        }
    }
}