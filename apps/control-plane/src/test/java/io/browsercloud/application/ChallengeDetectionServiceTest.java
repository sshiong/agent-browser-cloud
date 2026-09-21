package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.persistence.ChallengeEventEntity;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ChallengeDetectionServiceTest {

  @Mock private ChallengeEventJpaRepository events;
  @Mock private AuditApplicationService audit;

  @Test
  void createsAnInputFreeSingleClickEventBoundToTheVisualAnchor() {
    var service =
        new ChallengeDetectionService(
            events,
            new ObjectMapper(),
            audit,
            mock(org.springframework.jdbc.core.JdbcTemplate.class));
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
    var service =
        new ChallengeDetectionService(
            events,
            new ObjectMapper(),
            audit,
            mock(org.springframework.jdbc.core.JdbcTemplate.class));
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
    assertThat(captured.getValue().getTargetRef()).isEqualTo("target:7:otp");
    assertThat(captured.getValue().getTargetSummary()).isEqualTo("验证码需要人工提供或自行填写");
  }

  @Test
  void bindsVisualChallengeToABoundedNonSensitiveRegionOrLeavesItForHumanFallback() {
    var service =
        new ChallengeDetectionService(
            events,
            new ObjectMapper(),
            audit,
            mock(org.springframework.jdbc.core.JdbcTemplate.class));
    var target =
        new NodeEvent.InteractiveTarget(
            "target:7:visual",
            "button",
            "Select every image with a bicycle",
            new NodeEvent.Bounds(40, 60, 640, 480),
            true,
            true,
            false);

    assertThat(service.observe(envelope(), state("Verify", List.of(target)))).isPresent();

    var captured = ArgumentCaptor.forClass(ChallengeEventEntity.class);
    verify(events).save(captured.capture());
    assertThat(captured.getValue().getSuspectedType()).isEqualTo("IMAGE_SELECTION");
    assertThat(captured.getValue().getTargetRef()).isEqualTo("target:7:visual");
    assertThat(captured.getValue().getVisualAnchorHash()).hasSize(64);
    assertThat(captured.getValue().getStatus()).isEqualTo("TAKEOVER_REQUIRED");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void detectsOnlyExplicitlyAllowlistedFreshOpaqueSingleClickBoundary() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(List.of(Set.of("https://challenges.example.test")));
    var service = new ChallengeDetectionService(events, new ObjectMapper(), audit, jdbc);
    var frame =
        new NodeEvent.OpaqueFrame(
            "ofr_0123456789abcdef0123",
            "main",
            "https://challenges.example.test",
            new NodeEvent.Bounds(20, 30, 320, 180),
            "CROSS_ORIGIN",
            true,
            true,
            false,
            null,
            "BOUNDED_VISION_THEN_HUMAN_HANDOFF");
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            12,
            7,
            "https://example.test/login",
            "Verify you are human",
            List.of(
                new NodeEvent.BrowserTab("tab-1", "https://example.test/login", "Verify", true)),
            "tab-1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "COMPLETE",
            List.of(),
            "complete",
            2_000,
            true,
            "PERIODIC",
            "",
            List.of(),
            List.of(),
            true,
            List.of(),
            true,
            List.of(frame),
            true);

    assertThat(service.observe(envelope(), state)).isPresent();

    var captured = ArgumentCaptor.forClass(ChallengeEventEntity.class);
    verify(events).save(captured.capture());
    assertThat(captured.getValue().getSuspectedType()).isEqualTo("OPAQUE_FRAME_SINGLE_CLICK");
    assertThat(captured.getValue().getTargetRef()).isEqualTo(frame.frameRef());
    assertThat(captured.getValue().getStatus()).isEqualTo("CONFIRMED");
    assertThat(captured.getValue().getEvidence())
        .doesNotContain("https://challenges.example.test")
        .contains("\"opaqueFrameOriginHash\"");
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
