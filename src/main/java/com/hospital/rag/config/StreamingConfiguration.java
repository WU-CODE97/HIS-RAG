package com.hospital.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式问答所需的线程池。
 *
 * <p>SSE 接口把 emitter 返回给容器后，真正的检索与生成要放到独立线程里跑；
 * 直接用 Tomcat 请求线程会被异步机制阻塞住，等于回到同步模型。
 * 线程数按「同时可挂起的流式会话数」设置，队列满后由容器线程池自然形成背压。</p>
 */
@Configuration
public class StreamingConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService ragStreamExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(32, runnable -> {
            Thread thread = new Thread(runnable, "rag-stream-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
