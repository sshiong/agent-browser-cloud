package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.persistence.ChallengeEventEntity;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChallengeDetectionServiceTest {

  @Mock private ChallengeEventJpaRepository events;
  @Mock private AuditApplicationService audit;

  @Test
  void createsAnInputFreeSingleClickEventBoundToTheVisualAnchor() {
    var service = new ChallengeDetectionService(events, new ObjectMapper(), audit);
    var target =
        new NodeEvent.InteractiveTarget(
            "target:7:abc",
            "checkbox",
            "I'm not a robot",
            new NodeEvent.Bounds(10, 20, 100, 30),
            true,
            true,
            false);

    var result = service.observe(envelope(), state("Sign in", List.of(target)));

    assertThat(result).isPresent();
    var captured = ArgumentCaptor.forClass(ChallengeEventEntity.class);
    verify(events).save(captured.capture());
    assertThat(captured.getValue().getSuspectedType()).isEqualTo("SINGLE_CLICK");
    assertThat(captured.getValue().getStatus()).isEqualTo("CONFIRMED");
    assertThat(captured.getValue().getVisualAnchorHash())
        .isEqualTo("6dc7a8367775c215991f36f2d4553d38a64f6e5df58b813cc77d8d8e448647a5");
    assertThat(captured.getValue().getEvidence()).contains("\"automaticInteraction\":false");
    assertThat(captured.getValue().getEvidence()).contains("\"downstreamAutomationEligible\":true");
    verify(audit).append(any());
  }

  @Test
  void routesOtpToTakeoverAndDoesNotMistakeAnOrdinaryPasswordForOtp() {
    var service = new ChallengeDetectionService(events, new ObjectMapper(), audit);
    var ordinaryPassword =
        new NodeEvent.InteractiveTarget(
            "target:7:password", "textbox", "Password", null, true, true, true);

    assertThat(service.observe(envelope(), state("Sign in", List.of(ordinaryPassword)))).isEmpty();

    var otp =
        new NodeEvent.InteractiveTarget(
            "target:7:otp", "textbox", "Verification code", null, true, true, true);
    assertThat(service.observe(envelope(), state("Confirm", List.of(otp)))).isPresent();
    var captured = ArgumentCaptor.forClass(ChallengeEventEntity.class);
    verify(events).save(captured.capture());
    assertThat(captured.getValue().getSuspectedType()).isEqualTo("OTP");
    assertThat(captured.getValue().getStatus()).isEqualTo("TAKEOVER_REQUIRED");
    assertThat(captured.getValue().getTargetRef()).isNull();
  }

  private static NodeEventReceived envelope() {
    return new NodeEventReceived(
        "evt-test", "tenant-test", "ses-test", 1, 2, 3, 4, state("", List.of()));
  }

  private static NodeEvent.StateUpdated state(
      String title, List<NodeEvent.InteractiveTarget> targets) {
    return new NodeEvent.StateUpdated(
        "ses-test", 12, 7, "https://example.test", title, "hash-12", "COMPLETE", targets);
  }
}
