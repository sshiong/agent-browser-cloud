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
  @Mock private SessionResourceApplicationService resourceService;

  @Test
  void protectsBrowserAndWaitsForASafePointForTheClaimedPlacement() {
    var candidate =
        new NodePressureEvictionCandidate("ses_1234567890abcdef", "tenant-a", "node_critical");
    when(capacityService.claimPressureEviction()).thenReturn(Optional.of(candidate));
    var scheduler = new BrowserPressureRemediationScheduler(capacityService, resourceService);

    scheduler.remediateOne();

    verify(resourceService)
        .protectFromNodePressure("ses_1234567890abcdef", "tenant-a", "node_critical");
  }

  @Test
  void returnsTheClaimWhenPressureProtectionCannotBeCommittedYet() {
    var candidate =
        new NodePressureEvictionCandidate("ses_1234567890abcdef", "tenant-a", "node_critical");
    when(capacityService.claimPressureEviction()).thenReturn(Optional.of(candidate));
    doThrow(new IllegalStateException("active operation"))
        .when(resourceService)
        .protectFromNodePressure("ses_1234567890abcdef", "tenant-a", "node_critical");
    var scheduler = new BrowserPressureRemediationScheduler(capacityService, resourceService);

    scheduler.remediateOne();

    verify(capacityService).cancelPressureEviction("ses_1234567890abcdef");
  }
}
