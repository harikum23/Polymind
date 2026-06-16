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
@EnableAsync
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {

    @Bean
    public AsyncTaskExecutor knowledgeIndexingExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
