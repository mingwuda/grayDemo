FROM apache/rocketmq:4.9.4

# 切换到 root 用户进行文件操作
USER root
WORKDIR /home/rocketmq/rocketmq-4.9.4/bin

# 创建必要的目录（使用用户有权限的路径）
#RUN mkdir -p /home/rocketmq/conf \
#    && mkdir -p /home/rocketmq/store \
#    && mkdir -p /home/rocketmq/logs

# 复制启动脚本
COPY start-rocketmq.sh /tmp/
COPY broker.conf /home/rocketmq/

# 移动到正确位置并设置权限
RUN mv /tmp/start-rocketmq.sh /home/rocketmq/ \
    && chmod +x /home/rocketmq/start-rocketmq.sh

# 切换回 rocketmq 用户
USER rocketmq

# 使用自定义启动脚本
CMD ["/home/rocketmq/start-rocketmq.sh"]