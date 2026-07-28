package io.browsercloud.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.browsercloud.application.CapacityAdmissionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CoordinatorDensityTest {

  @Test
  void hotTenantRouteUsesAuthoritativePartitionCountAndEpoch() {
    var router = new CoordinatorShardRouter(16);
    var before = router.route("tenant-hot", "ses_1");
    var after = router.route("tenant-hot", "ses_1", 8, 2);

    assertThat(before.routeEpoch()).isEqualTo(1);
    assertThat(after.routeEpoch()).isEqualTo(2);
    assertThat(after.virtualPartition()).isBetween(0, 7);
    assertThat(router.route("tenant-hot", "ses_1", 8, 2)).isEqualTo(after);
    assertThatThrownBy(() -> router.route("tenant-hot", "ses_1", 0, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emergencyMessagePreemptsTelemetryInBoundedMailbox() {
    var mailbox = new VirtualActorMailbox<String>(2);
    assertThat(mailbox.offer("telemetry-1", 1)).isTrue();
    assertThat(mailbox.offer("telemetry-2", 1)).isTrue();
    assertThat(mailbox.offer("ordinary", 10)).isFalse();
    assertThat(mailbox.offer("emergency-stop", 100)).isTrue();
    assertThat(mailbox.poll()).contains("emergency-stop");
    assertThat(mailbox.canPassivate(Instant.now().plusSeconds(600), 300)).isFalse();
  }

  @Test
  void admissionUsesHysteresisAndBuildBoundCertificate() {
    var service = new CapacityAdmissionService("cp-sha256-test", 100, 85, 70);
    assertThat(service.update(86, false).admissionOpen()).isFalse();
    assertThat(service.update(80, false).admissionOpen()).isFalse();
    var reopened = service.update(70, false);
    assertThat(reopened.admissionOpen()).isTrue();
    assertThat(reopened.coordinatorBuildId()).isEqualTo("cp-sha256-test");
  }
}
