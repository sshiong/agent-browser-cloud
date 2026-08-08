package io.browsercloud.application;

import io.browsercloud.application.ProxyBindingColdProbeStore.ColdProbeClaim;
import io.browsercloud.application.ProxyBindingProbeNodeGateway.NoProbeNodeAvailableException;
import io.browsercloud.application.ProxyBindingProbeNodeGateway.ProbeRequest;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded cold-probe worker. PostgreSQL claims make it safe across Control Plane replicas. */
@Component
public class ProxyBindingColdProbeWorker {

  private static final Logger log = LoggerFactory.getLogger(ProxyBindingColdProbeWorker.class);

  private final ProxyBindingColdProbeStore store;
  private final ProxyBindingProbeNodeGateway gateway;
  private final int batchSize;
  private final Semaphore capacity;
  private final ExecutorService executor;

  public ProxyBindingColdProbeWorker(
      ProxyBindingColdProbeStore store,
      ProxyBindingProbeNodeGateway gateway,
      @Value("${proxy.health.cold-probe-batch-size:8}") int batchSize) {
    if (batchSize < 1 || batchSize > 64) {
      throw new IllegalArgumentException("Cold probe batch size must be between 1 and 64");
    }
    this.store = store;
    this.gateway = gateway;
    this.batchSize = batchSize;
    this.capacity = new Semaphore(batchSize);
    this.executor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("proxy-cold-probe-", 0).factory());
  }

  @Scheduled(fixedDelayString = "${proxy.health.cold-probe-dispatch-interval-ms:5000}")
  public void dispatchDue() {
    var available = Math.min(batchSize, capacity.availablePermits());
    if (available == 0) {
      return;
    }
    for (var claim : store.claimDue(available, Instant.now())) {
      if (!capacity.tryAcquire()) {
        store.retryUnavailable(claim, Instant.now());
        continue;
      }
      executor.submit(
          () -> {
            try {
              execute(claim);
            } finally {
              capacity.release();
            }
          });
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
  }

  private void execute(ColdProbeClaim claim) {
    try {
      var result =
          gateway.probe(
              new ProbeRequest(
                  claim.probeId(),
                  claim.tenantId(),
                  claim.bindingProfileId(),
                  claim.providerId(),
                  claim.region(),
                  claim.expectedExitIp(),
                  claim.credentialRef()));
      store.complete(claim, result, Instant.now());
    } catch (NoProbeNodeAvailableException exception) {
      store.retryUnavailable(claim, Instant.now());
      log.warn(
          "Cold Proxy Binding probe postponed: profile={}, reason={}",
          claim.bindingProfileId(),
          exception.getMessage());
    } catch (RuntimeException exception) {
      store.retryUnavailable(claim, Instant.now());
      log.error(
          "Cold Proxy Binding probe failed before a trustworthy result: profile={}",
          claim.bindingProfileId(),
          exception);
    }
  }
}
