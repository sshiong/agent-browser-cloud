package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerQueueWakeupServiceTest {

  private final WorkerQueueWakeupService service =
      new WorkerQueueWakeupService(mock(DataSource.class), 1_000);

  @AfterEach
  void close() {
    service.stop();
  }

  @Test
  void returnsAnImmediatelyAvailableClaimWithoutRegisteringAWaiter() throws Exception {
    var attempts = new AtomicInteger();

    var result =
        service
            .claim(
                WorkerQueueWakeupService.AGENT_EXECUTION,
                15,
                () -> {
                  attempts.incrementAndGet();
                  return Optional.of("job");
                },
                Optional::isPresent)
            .get(1, TimeUnit.SECONDS);

    assertThat(result).contains("job");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void notificationWakesOneLongPollAndRechecksTheAuthoritativeQueue() throws Exception {
    var attempts = new AtomicInteger();
    var result =
        service.claim(
            WorkerQueueWakeupService.AGENT_REVIEW,
            15,
            () -> attempts.incrementAndGet() == 1 ? Optional.<String>empty() : Optional.of("job"),
            Optional::isPresent);

    assertThat(result).isNotDone();
    service.signal(WorkerQueueWakeupService.AGENT_REVIEW);

    assertThat(result.get(1, TimeUnit.SECONDS)).contains("job");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void timeoutRechecksTheQueueSoLostNotificationsCannotLoseWork() throws Exception {
    var attempts = new AtomicInteger();

    var result =
        service
            .claim(
                WorkerQueueWakeupService.RUNTIME_VALIDATION,
                1,
                () ->
                    attempts.incrementAndGet() == 1 ? Optional.<String>empty() : Optional.of("job"),
                Optional::isPresent)
            .get(2, TimeUnit.SECONDS);

    assertThat(result).contains("job");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void oneNotificationDoesNotCreateAThunderingHerdWithinAnInstance() {
    var generation = service.generation(WorkerQueueWakeupService.CHALLENGE_VISUAL);
    var first =
        service.await(
            WorkerQueueWakeupService.CHALLENGE_VISUAL, generation, Duration.ofSeconds(10));
    var second =
        service.await(
            WorkerQueueWakeupService.CHALLENGE_VISUAL, generation, Duration.ofSeconds(10));

    service.signal(WorkerQueueWakeupService.CHALLENGE_VISUAL);

    assertThat(first).isDone();
    assertThat(second).isNotDone();
    service.signal(WorkerQueueWakeupService.CHALLENGE_VISUAL);
    assertThat(second).isDone();
  }
}
