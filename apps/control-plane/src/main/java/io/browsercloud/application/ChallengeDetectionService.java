package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.persistence.ChallengeEventEntity;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Conservative, input-free Challenge detector.
 *
 * <p>It stores only signal codes and hashes and has no browser-write capability. Eligible results
 * may be consumed by the separate, policy-bound visual automation service; high-risk results stay
 * on the explicit human-assist path.
 */
@Service
public class ChallengeDetectionService {

  private static final Pattern SINGLE_CLICK =
      Pattern.compile(
          "(?iu)(verify.{0,16}(you are|human)|i[’']?m not a robot|not a robot|human verification|"
              + "验证.{0,8}(真人|人类)|我不是机器人|人机验证)");
  private static final Pattern IMAGE_OR_PUZZLE =
      Pattern.compile(
          "(?iu)(select.{0,16}(image|picture)|image challenge|captcha.{0,12}(puzzle|image)|"
              + "drag.{0,12}puzzle|拼图|选择.{0,8}(图片|图像))");
  private static final Pattern OTP =
      Pattern.compile("(?iu)(one.?time.{0,8}(code|password)|verification code|otp|验证码|动态码)");
  private static final Pattern DEVICE =
      Pattern.compile("(?iu)(confirm.{0,12}device|device confirmation|approve.{0,12}device|设备确认)");
  private static final Pattern MULTI_ROUND =
      Pattern.compile("(?iu)(next challenge|another challenge|round [2-9]|多轮|下一轮|继续验证)");
  private static final Pattern PAYMENT =
      Pattern.compile("(?iu)(payment confirmation|confirm payment|支付确认|付款确认)");

  private final ChallengeEventJpaRepository events;
  private final ObjectMapper objectMapper;
  private final AuditApplicationService audit;
  private final JdbcTemplate jdbc;

  public ChallengeDetectionService(
      ChallengeEventJpaRepository events,
      ObjectMapper objectMapper,
      AuditApplicationService audit,
      JdbcTemplate jdbc) {
    this.events = events;
    this.objectMapper = objectMapper;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  /** Returns the event that must pause an active Agent after its verified step, if any. */
  public Optional<String> observe(NodeEventReceived envelope, NodeEvent.StateUpdated state) {
    if (!java.util.Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())
        || state.stateVersion() <= 0
        || state.targetRevision() <= 0) {
      return Optional.empty();
    }
    var now = Instant.now();
    events
        .findSupersededForUpdate(
            envelope.tenantId(), state.sessionId(), envelope.contextEpoch(), state.stateVersion())
        .forEach(
            event -> {
              event.supersede(now);
              events.save(event);
            });

    var classification = classify(envelope.tenantId(), state);
    if (classification == null) return Optional.empty();
    var duplicate =
        events.findDuplicate(
            envelope.tenantId(),
            state.sessionId(),
            envelope.contextEpoch(),
            state.stateVersion(),
            state.targetRevision(),
            classification.type(),
            classification.targetRef());
    if (duplicate.isPresent()) {
      return Optional.of(duplicate.orElseThrow().getChallengeEventId());
    }

    var eventId = id("chl_");
    var target = classification.target();
    var anchor =
        target != null
            ? visualAnchor(state, target)
            : classification.opaqueFrame() == null
                ? null
                : visualAnchor(state, classification.opaqueFrame());
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("detector", "conservative-accessibility-v1");
    evidence.put("signalCode", classification.signalCode());
    evidence.put("stateHash", state.stateHash());
    evidence.put("targetNameHash", target == null ? "NONE" : hash(target.name()));
    evidence.put(
        "opaqueFrameOriginHash",
        classification.opaqueFrame() == null
            ? "NONE"
            : hash(classification.opaqueFrame().origin()));
    evidence.put("sensitiveContentStored", false);
    evidence.put("automaticInteraction", false);
    evidence.put("downstreamAutomationEligible", automationEligible(classification.type()));
    var status = classification.oneClick() ? "CONFIRMED" : "TAKEOVER_REQUIRED";
    var event =
        new ChallengeEventEntity(
            eventId,
            envelope.tenantId(),
            state.sessionId(),
            envelope.contextEpoch(),
            state.stateVersion(),
            state.targetRevision(),
            classification.confidence(),
            json(evidence),
            classification.type(),
            classification.confidence() >= 0.9 ? "CHALLENGE_CONFIRMED" : "CHALLENGE_SUSPECTED",
            classification.targetRef(),
            classification.summary(),
            anchor,
            status,
            now,
            now.plusSeconds(120),
            now.plusSeconds(300));
    events.save(event);
    audit.append(
        new AuditApplicationService.AuditRecord(
            envelope.tenantId(),
            state.sessionId(),
            "CHALLENGE_DETECTED",
            "SYSTEM",
            "challenge-detector",
            "CHALLENGE_EVENT",
            eventId,
            "CLASSIFY",
            status,
            Map.of(
                "suspectedType",
                classification.type(),
                "confidence",
                classification.confidence(),
                "stateVersion",
                state.stateVersion(),
                "targetRevision",
                state.targetRevision(),
                "detectorAutomaticClickBudget",
                0,
                "downstreamAutomationEligible",
                automationEligible(classification.type())),
            envelope.eventId()));
    return Optional.of(eventId);
  }

  static String visualAnchor(NodeEvent.StateUpdated state, NodeEvent.InteractiveTarget target) {
    var bounds = target.bounds();
    if (bounds == null) return "";
    return PromptSecurityService.sha256(
        String.join(
            "\n",
            target.targetRef(),
            target.role(),
            canonical(bounds.x()),
            canonical(bounds.y()),
            canonical(bounds.width()),
            canonical(bounds.height())));
  }

  static String visualAnchor(NodeEvent.StateUpdated state, NodeEvent.OpaqueFrame frame) {
    var bounds = frame.bounds();
    if (bounds == null) return "";
    return PromptSecurityService.sha256(
        String.join(
            "\n",
            frame.frameRef(),
            frame.origin() == null ? "UNKNOWN" : frame.origin(),
            canonical(bounds.x()),
            canonical(bounds.y()),
            canonical(bounds.width()),
            canonical(bounds.height())));
  }

  private Classification classify(String tenantId, NodeEvent.StateUpdated state) {
    var aggregate =
        new StringBuilder(normalize(state.title())).append('\n').append(normalize(state.url()));
    state.targets().stream()
        .filter(target -> !target.sensitive() && target.name() != null)
        .forEach(target -> aggregate.append('\n').append(normalize(target.name())));
    var text = aggregate.toString();
    if (PAYMENT.matcher(text).find()) {
      return takeover("PAYMENT_CONFIRMATION", "PAYMENT_CONFIRMATION_SIGNAL", "支付确认需要人工接管");
    }
    var otpTarget =
        state.targets().stream()
            .filter(NodeEvent.InteractiveTarget::sensitive)
            .filter(target -> target.name() != null && OTP.matcher(normalize(target.name())).find())
            .filter(target -> target.visible() && target.enabled())
            .filter(
                target -> java.util.Set.of("textbox", "combobox").contains(safeRole(target.role())))
            .findFirst()
            .orElse(null);
    if (OTP.matcher(text).find() || otpTarget != null) {
      return new Classification(
          "OTP", "OTP_OR_SENSITIVE_INPUT_SIGNAL", "验证码需要人工提供或自行填写", 0.98, otpTarget, null, false);
    }
    if (IMAGE_OR_PUZZLE.matcher(text).find()) {
      var type = text.matches("(?is).*(puzzle|drag|拼图).*") ? "PUZZLE" : "IMAGE_SELECTION";
      var visualTarget =
          state.targets().stream()
              .filter(this::eligibleVisualChallengeTarget)
              .filter(target -> IMAGE_OR_PUZZLE.matcher(normalize(target.name())).find())
              .findFirst()
              .orElse(null);
      return new Classification(
          type, "MULTI_STEP_VISUAL_SIGNAL", "多步骤视觉挑战需要受限视觉处理", 0.98, visualTarget, null, false);
    }
    if (DEVICE.matcher(text).find()) {
      return takeover("DEVICE_CONFIRMATION", "DEVICE_CONFIRMATION_SIGNAL", "设备确认需要人工接管");
    }
    if (MULTI_ROUND.matcher(text).find()) {
      return takeover("MULTI_ROUND", "MULTI_ROUND_SIGNAL", "多轮挑战需要人工接管");
    }
    var structuralSingleClick =
        state.targets().stream()
            .filter(this::eligibleSingleClickTarget)
            .filter(target -> SINGLE_CLICK.matcher(normalize(target.name())).find())
            .findFirst();
    if (structuralSingleClick.isPresent()) {
      var target = structuralSingleClick.orElseThrow();
      return new Classification(
          "SINGLE_CLICK",
          "EXPLICIT_SINGLE_CLICK_ACCESSIBILITY_SIGNAL",
          "单次人机验证目标（" + safeRole(target.role()) + "）",
          0.99,
          target,
          null,
          true);
    }
    if (SINGLE_CLICK.matcher(text).find() && state.opaqueFrameEvidenceFresh()) {
      var allowedOrigins = opaqueFrameClickOrigins(tenantId, state.sessionId());
      var frames =
          state.opaqueFrames().stream()
              .filter(this::eligibleOpaqueFrame)
              .filter(frame -> frame.origin() != null && allowedOrigins.contains(frame.origin()))
              .toList();
      if (frames.size() == 1) {
        var frame = frames.getFirst();
        return new Classification(
            "OPAQUE_FRAME_SINGLE_CLICK",
            "EXPLICIT_OPAQUE_FRAME_SINGLE_CLICK_SIGNAL",
            "已授权跨域验证边界的单次视觉点击",
            0.99,
            null,
            frame,
            true);
      }
    }
    return null;
  }

  private boolean eligibleSingleClickTarget(NodeEvent.InteractiveTarget target) {
    return target.visible()
        && target.enabled()
        && !target.sensitive()
        && target.bounds() != null
        && target.bounds().width() > 0
        && target.bounds().height() > 0
        && java.util.Set.of("button", "checkbox").contains(target.role().toLowerCase(Locale.ROOT));
  }

  private boolean eligibleVisualChallengeTarget(NodeEvent.InteractiveTarget target) {
    return target.visible()
        && target.enabled()
        && !target.sensitive()
        && target.name() != null
        && target.bounds() != null
        && target.bounds().x() >= 0
        && target.bounds().y() >= 0
        && target.bounds().width() >= 1
        && target.bounds().height() >= 1
        && target.bounds().width() <= 2048
        && target.bounds().height() <= 2048
        && target.bounds().width() * target.bounds().height() <= 2_097_152;
  }

  private boolean eligibleOpaqueFrame(NodeEvent.OpaqueFrame frame) {
    return frame.visible()
        && frame.inViewport()
        && !frame.occluded()
        && frame.bounds() != null
        && frame.bounds().x() >= 0
        && frame.bounds().y() >= 0
        && frame.bounds().width() >= 1
        && frame.bounds().height() >= 1
        && frame.bounds().width() <= 2048
        && frame.bounds().height() <= 2048
        && frame.bounds().width() * frame.bounds().height() <= 2_097_152
        && "BOUNDED_VISION_THEN_HUMAN_HANDOFF".equals(frame.interactionStrategy());
  }

  private Set<String> opaqueFrameClickOrigins(String tenantId, String sessionId) {
    return jdbc
        .query(
            """
            SELECT challenge_opaque_frame_click_origins
            FROM sessions
            WHERE id=? AND tenant_id=? AND deleted_at IS NULL
              AND challenge_automation_enabled
              AND challenge_opaque_frame_click_enabled
            """,
            (result, row) -> {
              try {
                return Set.copyOf(
                    objectMapper.readValue(
                        result.getString("challenge_opaque_frame_click_origins"),
                        new TypeReference<java.util.List<String>>() {}));
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Opaque frame click policy is invalid", exception);
              }
            },
            sessionId,
            tenantId)
        .stream()
        .findFirst()
        .orElse(Set.of());
  }

  private static Classification takeover(String type, String signal, String summary) {
    return new Classification(type, signal, summary, 0.98, null, null, false);
  }

  private static boolean automationEligible(String type) {
    return java.util.Set.of(
            "SINGLE_CLICK", "OPAQUE_FRAME_SINGLE_CLICK", "IMAGE_SELECTION", "PUZZLE", "MULTI_ROUND")
        .contains(type);
  }

  private static String safeRole(String role) {
    return role == null || role.isBlank() ? "control" : role.toLowerCase(Locale.ROOT);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  private static String canonical(double value) {
    return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static String hash(String value) {
    return PromptSecurityService.sha256(value == null ? "" : value);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("challenge evidence is not serializable", exception);
    }
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record Classification(
      String type,
      String signalCode,
      String summary,
      double confidence,
      NodeEvent.InteractiveTarget target,
      NodeEvent.OpaqueFrame opaqueFrame,
      boolean oneClick) {
    String targetRef() {
      return target != null
          ? target.targetRef()
          : opaqueFrame == null ? null : opaqueFrame.frameRef();
    }
  }
}
