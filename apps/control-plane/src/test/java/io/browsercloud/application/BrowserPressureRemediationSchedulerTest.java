package io.browsercloud.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.application.BrowserCapacityApplicationService.NodePressureEvictionCandidate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrowserPressureRemediationSchedulerTest {

  @Mock private BrowserCapacityApplicationService capacityService;
  @Mock private SessionApplicationService sessionService;

  @Test
  void requestsOneFencedTerminationForTheClaimedPlacement() {
    var candidate =
        new NodePressureEvictionCandidate("ses_1234567890abcdef", "tenant-a", "node_critical");
    when(capacityService.claimPressureEviction()).thenReturn(Optional.of(candidate));
    var scheduler = new BrowserPressureRemediationScheduler(capacityService, sessionService);

    scheduler.remediateOne();

    verify(sessionService)
        .terminateForNodePressure("ses_1234567890abcdef", "tenant-a", "node_critical");
  }

  @Test
  void returnsTheClaimWhenCoordinatorCannotSafelyTerminateYet() {
    var candidate =
        new NodePressureEvictionCandidate("ses_1234567890abcdef", "tenant-a", "node_critical");
    when(capacityService.claimPressureEviction()).thenReturn(Optional.of(candidate));
    doThrow(new IllegalStateException("active operation"))
        .when(sessionService)
        .terminateForNodePressure("ses_1234567890abcdef", "tenant-a", "node_critical");
    var scheduler = new BrowserPressureRemediationScheduler(capacityService, sessionService);

    scheduler.remediateOne();

    verify(capacityService).cancelPressureEviction("ses_1234567890abcdef");
  }
}
