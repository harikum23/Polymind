package com.polymind.knowledge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/** Enables config properties and a virtual-thread executor for background indexing. */
@Configuration
// proxyTargetClass=true so async AOP uses CGLIB; otherwise JDK proxies are forced
// app-wide and the servlet ApiKeyAuthFilter (a Filter) can't be injected by its concrete type.
@EnableAsync(proxyTargetClass = true)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {

    @Bean
    public AsyncTaskExecutor knowledgeIndexingExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
