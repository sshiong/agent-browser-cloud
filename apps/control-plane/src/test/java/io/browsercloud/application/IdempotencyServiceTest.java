package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.ProviderEvidenceOutcome.MATCH;
import static io.browsercloud.api.BusinessRecoveryModels.ProviderEvidenceType.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BusinessRecoveryModels.SubmitProviderEvidenceRequest;
import io.browsercloud.persistence.ApiIdempotencyEntity;
import io.browsercloud.persistence.ApiIdempotencyJpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void replaysTheFirstCommittedAgentCancellationResponse() {
    var repository = mock(ApiIdempotencyJpaRepository.class);
    var requestHash = new AtomicReference<String>();
    when(repository.claim(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(Instant.class)))
        .thenAnswer(
            invocation -> {
              requestHash.set(invocation.getArgument(4));
              return 0;
            });
    var existing = new ApiIdempotencyEntity();
    existing.setRequestHash("pending");
    existing.setResponsePayload("{\"taskId\":\"agt_example\"}");
    when(repository.findByTenantIdAndOperationTypeAndIdempotencyKey(
            "tenant-a", "CANCEL_AGENT_TASK:agt_example", "idem-cancel"))
        .thenAnswer(
            invocation -> {
              existing.setRequestHash(requestHash.get());
              return Optional.of(existing);
            });

    var claim =
        new IdempotencyService(repository)
            .claimAgentCancellation("tenant-a", "agt_example", "actor-a", "idem-cancel");

    assertThat(claim.owner()).isFalse();
    assertThat(claim.responsePayload()).isEqualTo("{\"taskId\":\"agt_example\"}");
  }

  @Test
  void persistsTheAgentCancellationResponseOnce() {
    var repository = mock(ApiIdempotencyJpaRepository.class);
    when(repository.completeResponse(
            "tenant-a",
            "CANCEL_AGENT_TASK:agt_example",
            "idem-cancel",
            "agt_example",
            "{\"state\":\"FAILED\"}"))
        .thenReturn(1);
    var service = new IdempotencyService(repository);

    service.completeAgentCancellation(
        "tenant-a", "agt_example", "idem-cancel", "{\"state\":\"FAILED\"}");

    verify(repository)
        .completeResponse(
            "tenant-a",
            "CANCEL_AGENT_TASK:agt_example",
            "idem-cancel",
            "agt_example",
            "{\"state\":\"FAILED\"}");
  }
}
