package com.example.enums;

/**
 * 发布状态枚举
 * 定义了发布流程中的各个状态，以及在每个状态下灰度环境和生产环境消费者的消费行为
 */
public enum ReleaseState {

    /**
     * 仅灰度可访问
     * 灰度环境：消费，生产环境：不消费
     */
    GRAY_ACCESSABLE("GRAY_ACCESSABLE", true, false),


    /**
     * 仅生产可访问
     * 灰度环境：不消费，生产环境：消费
     */
    PROD_ACCESSABLE("PROD_ACCESSABLE", false, true),

    /**
     * 全部可访问
     * 灰度环境：消费，生产环境：消费
     */
    ALL_ACCESSABLE("ALL_ACCESSABLE", true, true);

    private final String stateName;
    private final boolean grayConsumerEnabled;
    private final boolean prodConsumerEnabled;

    ReleaseState(String stateName, boolean grayConsumerEnabled, boolean prodConsumerEnabled) {
        this.stateName = stateName;
        this.grayConsumerEnabled = grayConsumerEnabled;
        this.prodConsumerEnabled = prodConsumerEnabled;
    }

    public String getStateName() {
        return stateName;
    }

    /**
     * 根据节点类型判断是否应该消费消息
     * @param nodeType 节点类型（GRAY_CONSUMER 或其他）
     * @return true表示应该消费，false表示不应该消费
     */
    public boolean shouldConsume(String nodeType) {
        if ("GRAY_CONSUMER".equals(nodeType)) {
            return grayConsumerEnabled;
        } else {
            return prodConsumerEnabled;
        }
    }

    /**
     * 根据状态名称获取枚举值
     * @param stateName 状态名称
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果找不到匹配的状态名称
     */
    public static ReleaseState fromStateName(String stateName) {
        for (ReleaseState state : values()) {
            if (state.getStateName().equals(stateName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Invalid release state: " + stateName + ". Valid states are: GRAY_ACCESSABLE, PROD_ACCESSABLE, ALL_ACCESSABLE");
    }
}