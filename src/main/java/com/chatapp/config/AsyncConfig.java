package com.chatapp.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pmchat-async-");
        executor.initialize();
        return executor;
    }

    /**
     * 离线推送专用线程池：推送服务器慢或连不上时不能拖住发消息的请求，
     * 也不能挤占图片生成等其它异步任务。排满时丢弃最早的推送并记日志，绝不回退到调用方线程。
     */
    @Bean(name = "pushExecutor")
    public Executor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("pmchat-push-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            if (pool.isShutdown()) {
                return;
            }
            pool.getQueue().poll();
            LoggerFactory.getLogger(AsyncConfig.class).warn("推送队列已满，丢弃最早的一条推送");
            pool.execute(task);
        });
        executor.initialize();
        return executor;
    }
}
