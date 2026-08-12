package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionResourceAdjustmentEntityTest {

  @Test
  void followsTheStrictAcknowledgementLifecycle() {
    var requestedAt = Instant.parse("2026-08-12T00:00:00Z");
    var entity = adjustment(requestedAt);

    assertThat(entity.getState()).isEqualTo("REQUESTED");
    assertThat(entity.markExecuting(requestedAt.plusSeconds(1))).isTrue();
    assertThat(entity.getState()).isEqualTo("EXECUTING");
    assertThat(entity.acknowledge(requestedAt.plusSeconds(2))).isTrue();
    assertThat(entity.getState()).isEqualTo("ACKNOWLEDGED");
    assertThat(entity.commit(requestedAt.plusSeconds(3))).isTrue();
    assertThat(entity.getState()).isEqualTo("COMMITTED");
    assertThat(entity.getCompletedAt()).isEqualTo(requestedAt.plusSeconds(3));
    assertThat(entity.fail("LATE_FAILURE", requestedAt.plusSeconds(4))).isFalse();
  }

  @Test
  void cannotCommitBeforeAnAuthoritativeAck() {
    var entity = adjustment(Instant.now());

    assertThatThrownBy(() -> entity.commit(Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_COMMITTABLE");
  }

  @Test
  void terminalFailureKeepsItsExactCode() {
    var entity = adjustment(Instant.now());

    assertThat(entity.fail("STALE_ROUTE_EPOCH", Instant.now())).isTrue();
    assertThat(entity.getState()).isEqualTo("FAILED");
    assertThat(entity.getFailureCode()).isEqualTo("STALE_ROUTE_EPOCH");
    assertThat(entity.getCompletedAt()).isNotNull();
  }

  @Test
  void timedOutAdjustmentCanBeLinkedToACompensatingReconciliation() {
    var now = Instant.now();
    var entity = adjustment(now);
    entity.markExecuting(now.plusSeconds(1));
    entity.fail("NODE_ACK_TIMEOUT", now.plusSeconds(90));

    assertThat(entity.reconcile("op_reconcile0000001", now.plusSeconds(95))).isTrue();
    assertThat(entity.getState()).isEqualTo("RECONCILED");
    assertThat(entity.getFailureCode()).isEqualTo("NODE_ACK_TIMEOUT");
    assertThat(entity.getReconciliationOperationId()).isEqualTo("op_reconcile0000001");
    assertThat(entity.getReconciledAt()).isEqualTo(now.plusSeconds(95));
  }

  @Test
  void nonTimeoutFailureCannotBeReconciled() {
    var entity = adjustment(Instant.now());
    entity.fail("RESOURCE_ADJUSTMENT_ACK_MISMATCH", Instant.now());

    assertThatThrownBy(() -> entity.reconcile("op_reconcile0000001", Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_RECONCILABLE");
  }

  private static SessionResourceAdjustmentEntity adjustment(Instant now) {
    return SessionResourceAdjustmentEntity.requested(
        "op_resource00000001",
        "ses_resource0000001",
        "tenant-test",
        "SUSTAINED_CPU_PRESSURE",
        "{\"cpuMillis\":600}",
        "{\"cpuMillis\":900}",
        now);
  }
}
