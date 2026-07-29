package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.ProviderEvidenceOutcome.MATCH;
import static io.browsercloud.api.BusinessRecoveryModels.ProviderEvidenceType.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BusinessRecoveryModels.SubmitProviderEvidenceRequest;
import io.browsercloud.persistence.ApiIdempotencyJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

  @Test
  void hashesProviderEvidenceRequestsContainingInstant() {
    var repository = mock(ApiIdempotencyJpaRepository.class);
    when(repository.claim(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(Instant.class)))
        .thenReturn(1);
    var service = new IdempotencyService(repository);
    var request =
        new SubmitProviderEvidenceRequest(
            7,
            19,
            ACCOUNT,
            "current-account",
            "crm-provider",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            MATCH,
            "provider-observation-123",
            Instant.parse("2026-07-30T00:00:00Z"));

    var claimed =
        service.claimBusinessRecoveryProviderEvidence(
            "tenant-a", "session-a", "adapter-a", "idem-a", request, "evidence-a");

    assertThat(claimed).isEqualTo("evidence-a");
  }
}
