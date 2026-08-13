package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.persistence.ApplicationRecoveryContractEntity;
import io.browsercloud.persistence.ApplicationRecoveryContractJpaRepository;
import io.browsercloud.persistence.ApplicationRecoveryContractRevisionEntity;
import io.browsercloud.persistence.ApplicationRecoveryContractRevisionJpaRepository;
import io.browsercloud.persistence.SessionApplicationBindingEntity;
import io.browsercloud.persistence.SessionApplicationBindingJpaRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaBrowserTransactionPolicyRepositoryTest {

  @Test
  void resolvesExactBoundRevisionAndProducesDeterministicHash() {
    var bindings = mock(SessionApplicationBindingJpaRepository.class);
    var heads = mock(ApplicationRecoveryContractJpaRepository.class);
    var revisions = mock(ApplicationRecoveryContractRevisionJpaRepository.class);
    var binding =
        new SessionApplicationBindingEntity(
            "ses-a", "tenant-a", "crm", "arc_1234567890abcdefghij", 7, java.time.Instant.now());
    var head = mock(ApplicationRecoveryContractEntity.class);
    var revision = mock(ApplicationRecoveryContractRevisionEntity.class);
    when(bindings.findBySessionIdAndTenantId("ses-a", "tenant-a")).thenReturn(Optional.of(binding));
    when(heads.findById("arc_1234567890abcdefghij")).thenReturn(Optional.of(head));
    when(head.getTenantId()).thenReturn("tenant-a");
    when(revisions.findByContractIdAndContractVersionAndTenantIdAndApplicationId(
            "arc_1234567890abcdefghij", 7, "tenant-a", "crm"))
        .thenReturn(Optional.of(revision));
    when(revision.getExpectedOrigins()).thenReturn("[\"https://crm.example.test\"]");
    when(revision.getPaymentSecurityRoutePrefixes()).thenReturn("[\"/api/authorize\"]");
    when(revision.getCriticalTransactionRoutePrefixes()).thenReturn("[\"/cases/finalize\"]");
    when(revision.getContractVersion()).thenReturn(7L);
    var repository =
        new JpaBrowserTransactionPolicyRepository(bindings, heads, revisions, new ObjectMapper());

    var result = repository.find("ses-a", "tenant-a");

    assertThat(result.version()).isEqualTo(7);
    assertThat(result.expectedOrigins()).containsExactly("https://crm.example.test");
    assertThat(result.paymentSecurityRoutePrefixes()).containsExactly("/api/authorize");
    assertThat(result.criticalTransactionRoutePrefixes()).containsExactly("/cases/finalize");
    assertThat(result.policyHash())
        .isEqualTo(
            JpaBrowserTransactionPolicyRepository.hash(
                7,
                List.of("https://crm.example.test"),
                List.of("/api/authorize"),
                List.of("/cases/finalize")));
    assertThat(result.policyHash()).matches("[0-9a-f]{64}");
  }
}
