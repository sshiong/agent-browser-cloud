package io.browsercloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExclusiveOperationTest {

  @Test
  void shouldBeActiveWhenStateIsActive() {
    var operation = createOperation(OperationState.ACTIVE);
    assertThat(operation.isActive()).isTrue();
  }

  @Test
  void shouldNotBeActiveWhenStateIsCommitted() {
    var operation = createOperation(OperationState.COMMITTED);
    assertThat(operation.isActive()).isFalse();
  }

  @Test
  void shouldNotBeExpiredBeforeDeadline() {
    var operation = createOperationWithDeadline(Instant.now().plusSeconds(60));
    assertThat(operation.isExpired(Instant.now())).isFalse();
  }

  @Test
  void shouldBeExpiredAfterDeadline() {
    var operation = createOperationWithDeadline(Instant.now().minusSeconds(60));
    assertThat(operation.isExpired(Instant.now())).isTrue();
  }

  @Test
  void shouldCreateNewState() {
    var operation = createOperation(OperationState.ACTIVE);
    var committed = operation.withState(OperationState.COMMITTED);

    assertThat(committed.state()).isEqualTo(OperationState.COMMITTED);
    assertThat(committed.completedAt()).isNotNull();
    assertThat(operation.completedAt()).isNull();
  }

  @Test
  void shouldCreateNewPhase() {
    var operation = createOperation(OperationState.ACTIVE);
    var executing = operation.withPhase(OperationPhase.EXECUTING);

    assertThat(executing.phase()).isEqualTo(OperationPhase.EXECUTING);
    assertThat(executing.state()).isEqualTo(OperationState.ACTIVE);
  }

  private ExclusiveOperation createOperation(OperationState state) {
    return new ExclusiveOperation(
        "op-1",
        "ses-1",
        OwnerType.AGENT,
        "agent-1",
        OperationMode.AGENT_INTERACTIVE,
        0,
        1,
        1,
        1,
        null,
        true,
        true,
        OperationPhase.PREPARING,
        state,
        Set.of(),
        Instant.now().plusSeconds(300),
        Instant.now(),
        null);
  }

  private ExclusiveOperation createOperationWithDeadline(Instant deadline) {
    return new ExclusiveOperation(
        "op-1",
        "ses-1",
        OwnerType.AGENT,
        "agent-1",
        OperationMode.AGENT_INTERACTIVE,
        0,
        1,
        1,
        1,
        null,
        true,
        true,
        OperationPhase.PREPARING,
        OperationState.ACTIVE,
        Set.of(),
        deadline,
        Instant.now(),
        null);
  }
}
