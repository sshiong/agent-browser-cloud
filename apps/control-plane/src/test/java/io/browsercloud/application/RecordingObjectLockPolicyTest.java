package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.application.EnterpriseOperationsApplicationService.GovernanceRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RecordingObjectLockPolicyTest {

  @Test
  void productionFailsClosedWithoutAnObjectLockRetentionFloor() {
    assertThatThrownBy(() -> service(0, "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Production requires");
  }

  @Test
  void recordingPolicyCannotExpireBeforeTheObjectLockSafetyFloor() {
    var service = service(31, "production");

    assertThatThrownBy(
            () ->
                service.upsertRetention(
                    "tenant-test",
                    new UpsertRetentionPolicyRequest(
                        "REMOTE_DESKTOP_RECORDING", 30, false, "primary"),
                    "security-admin"))
        .isInstanceOf(GovernanceRejectedException.class)
        .hasMessage("OBJECT_LOCK_RETENTION_MINIMUM");
  }

  private static EnterpriseOperationsApplicationService service(
      int minimumRetentionDays, String environment) {
    return new EnterpriseOperationsApplicationService(
        mock(JdbcTemplate.class),
        new ObjectMapper(),
        mock(AuditApplicationService.class),
        mock(ReleaseFreezeApplicationService.class),
        mock(RecoveryGameDayGovernanceApplicationService.class),
        "test-signing-key",
        "test-key-id",
        false,
        minimumRetentionDays,
        environment);
  }
}
