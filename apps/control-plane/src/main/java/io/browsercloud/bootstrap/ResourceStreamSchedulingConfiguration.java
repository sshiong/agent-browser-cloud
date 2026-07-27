package io.browsercloud.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates live resource delivery from lifecycle, workflow and outbox scheduler latency. */
@Configuration
public class ResourceStreamSchedulingConfiguration {

  @Bean(name = "resourceStreamTaskScheduler")
  public ThreadPoolTaskScheduler resourceStreamTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("resource-stream-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }
}
