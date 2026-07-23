package com.devknow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局线程池配置。
 *
 * <p>统一管理所有异步线程池，避免：
 * <ul>
 *   <li>ForkJoinPool.commonPool 被各处 CompletableFuture 抢占</li>
 *   <li>Spring @Async 使用默认 SimpleAsyncTaskExecutor（每任务新建线程）</li>
 *   <li>各处自行 newCachedThreadPool 无关闭</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    /**
     * Spring @Async 专用线程池。
     *
     * @deprecated JDK 21 虚拟线程已启用（{@code spring.threads.virtual.enabled=true}），
     *             {@code @Async} 自动使用虚拟线程，不再需要自定义线程池。
     *             此 Bean 保留仅为兼容可能显式引用它的代码，将在下次清理中移除。
     */
    @Deprecated
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 通用异步操作线程池（SSE 流式任务等）。
     * 供 CompletableFuture.runAsync 使用，避免抢占 commonPool。
     *
     * @deprecated 建议迁移至虚拟线程：
     *             {@code Executors.newVirtualThreadPerTaskExecutor()}。
     *             保留此 Bean 供 CPU 密集型任务使用（虚拟线程不适合 CPU 密集场景）。
     */
    @Deprecated
    @Bean(destroyMethod = "shutdown")
    public ExecutorService asyncExecutor() {
        return Executors.newFixedThreadPool(8, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "async-worker-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }
}
