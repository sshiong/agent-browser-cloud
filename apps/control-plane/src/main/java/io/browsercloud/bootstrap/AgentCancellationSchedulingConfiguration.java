package io.browsercloud.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps cancellation delivery independent from long-running workflow and Node RPC schedulers. */
@Configuration
public class AgentCancellationSchedulingConfiguration {

  @Bean(name = "agentCancellationTaskScheduler")
  public ThreadPoolTaskScheduler agentCancellationTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("agent-cancel-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(5);
    return scheduler;
  }
}
