package io.browsercloud.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionEvidenceApplicationServiceTest {

  @Test
  void completesGovernanceCaptureForStateFencedChallengeEvidence() {
    var jdbc = mock(JdbcTemplate.class);
    var governance = mock(SessionEvidenceGovernanceStore.class);
    var screenshots = mock(AgentBrowserScreenshotStore.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    var service =
        new SessionEvidenceApplicationService(
            jdbc, mock(SessionRepository.class), governance, screenshots);
    var evidence =
        new NodeEvent.EvidenceCaptured(
            "ses_test",
            "evd_1234567890abcdef",
            "CHALLENGE_SCREENSHOT",
            "cap_1234567890abcdefghij",
            "challenge-screenshot",
            "cmd_1234567890abcdefghij",
            "a".repeat(64),
            2048,
            "tenants/tenant-test/evidence.jpeg",
            1_785_283_200_000L,
            true,
            "COMMITTED",
            "",
            "MASKED",
            1,
            "CHALLENGE_REGION",
            9,
            4,
            "b".repeat(64),
            "tab-challenge",
            1280,
            720,
            2,
            10,
            20,
            300,
            180,
            "VIEWPORT");

    service.record("tenant-test", "evt-test", evidence);

    verify(governance)
        .completeCaptureFromEvidence(
            eq("tenant-test"),
            eq("ses_test"),
            eq("cmd_1234567890abcdefghij"),
            eq("evd_1234567890abcdef"),
            eq("COMMITTED"),
            eq(""),
            any());
  }
}
