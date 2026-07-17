package com.devknow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 影响分析线程池配置。
 *
 * <p>独立于 ForkJoinPool.commonPool，避免抢占 Chat SSE 线程资源。
 * Spring 管理生命周期，应用关闭时自动 shutdown。
 */
@Configuration
public class ImpactConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService impactExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "impact-worker");
            t.setDaemon(true);
            return t;
        });
    }
}
