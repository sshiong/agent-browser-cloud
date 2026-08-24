package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaOperationRepositoryTest {

  @Test
  void shouldRestorePersistedCapabilityFences() {
    var jpa = mock(ExclusiveOperationJpaRepository.class);
    var entity = operation("[\"browser.file.upload\",\"state.read\"]");
    when(jpa.findBySessionIdAndState("ses_test", "ACTIVE")).thenReturn(Optional.of(entity));

    var operation = new JpaOperationRepository(jpa).findActive("ses_test").orElseThrow();

    assertThat(operation.allowedCapabilities())
        .containsExactlyInAnyOrder("browser.file.upload", "state.read");
  }

  @Test
  void shouldFailClosedForMalformedPersistedCapabilities() {
    var jpa = mock(ExclusiveOperationJpaRepository.class);
    when(jpa.findBySessionIdAndState("ses_test", "ACTIVE"))
        .thenReturn(Optional.of(operation("not-json")));

    assertThatThrownBy(() -> new JpaOperationRepository(jpa).findActive("ses_test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("capabilities");
  }

  private static ExclusiveOperationEntity operation(String capabilities) {
    var entity = new ExclusiveOperationEntity();
    entity.setOperationId("op_1234567890abcdef");
    entity.setSessionId("ses_test");
    entity.setOwnerType("AGENT");
    entity.setActorId("integration-agent");
    entity.setMode("AGENT_INTERACTIVE");
    entity.setPriority(40);
    entity.setCoordinatorTerm(1);
    entity.setContextEpoch(1);
    entity.setOperationEpoch(2);
    entity.setCancellable(false);
    entity.setPreemptible(false);
    entity.setPhase("PREPARING");
    entity.setState("ACTIVE");
    entity.setAllowedCapabilities(capabilities);
    entity.setDeadline(Instant.now().plusSeconds(60));
    entity.setCreatedAt(Instant.now());
    return entity;
  }
}
