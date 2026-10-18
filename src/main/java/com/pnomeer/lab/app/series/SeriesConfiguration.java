package com.pnomeer.lab.app.series;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(SeriesProperties.class)
public class SeriesConfiguration {
    /** One coordinator thread: repetitions never compete with each other for admission. */
    @org.springframework.context.annotation.DependsOn("experimentRunner")
    @Bean(name = "seriesTaskExecutor")
    public TaskExecutor seriesTaskExecutor(SeriesProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("series-");
        // Shutdown interrupts the coordinator; the unfinished series is recorded as INTERRUPTED.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
