package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.UUID;
import java.util.regex.Pattern;
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

  public ChallengeDetectionService(
      ChallengeEventJpaRepository events,
      ObjectMapper objectMapper,
      AuditApplicationService audit) {
    this.events = events;
    this.objectMapper = objectMapper;
    this.audit = audit;
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

    var classification = classify(state);
    if (classification == null) return Optional.empty();
    var duplicate =
        events.findDuplicate(
            envelope.tenantId(),
            state.sessionId(),
            envelope.contextEpoch(),
            state.stateVersion(),
            state.targetRevision(),
            classification.type(),
            classification.target() == null ? null : classification.target().targetRef());
    if (duplicate.isPresent()) {
      return Optional.of(duplicate.orElseThrow().getChallengeEventId());
    }

    var eventId = id("chl_");
    var target = classification.target();
    var anchor = target == null ? null : visualAnchor(state, target);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("detector", "conservative-accessibility-v1");
    evidence.put("signalCode", classification.signalCode());
    evidence.put("stateHash", state.stateHash());
    evidence.put("targetNameHash", target == null ? "NONE" : hash(target.name()));
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
            target == null ? null : target.targetRef(),
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

  private Classification classify(NodeEvent.StateUpdated state) {
    var aggregate =
        new StringBuilder(normalize(state.title())).append('\n').append(normalize(state.url()));
    state.targets().stream()
        .filter(target -> !target.sensitive() && target.name() != null)
        .forEach(target -> aggregate.append('\n').append(normalize(target.name())));
    var text = aggregate.toString();
    if (PAYMENT.matcher(text).find()) {
      return takeover("PAYMENT_CONFIRMATION", "PAYMENT_CONFIRMATION_SIGNAL", "支付确认需要人工接管");
    }
    if (OTP.matcher(text).find()
        || state.targets().stream()
            .filter(NodeEvent.InteractiveTarget::sensitive)
            .map(NodeEvent.InteractiveTarget::name)
            .filter(java.util.Objects::nonNull)
            .map(ChallengeDetectionService::normalize)
            .anyMatch(name -> OTP.matcher(name).find())) {
      return takeover("OTP", "OTP_OR_SENSITIVE_INPUT_SIGNAL", "验证码或敏感输入需要人工接管");
    }
    if (IMAGE_OR_PUZZLE.matcher(text).find()) {
      var type = text.matches("(?is).*(puzzle|drag|拼图).*") ? "PUZZLE" : "IMAGE_SELECTION";
      return takeover(type, "MULTI_STEP_VISUAL_SIGNAL", "多步骤视觉挑战需要人工接管");
    }
    if (DEVICE.matcher(text).find()) {
      return takeover("DEVICE_CONFIRMATION", "DEVICE_CONFIRMATION_SIGNAL", "设备确认需要人工接管");
    }
    if (MULTI_ROUND.matcher(text).find()) {
      return takeover("MULTI_ROUND", "MULTI_ROUND_SIGNAL", "多轮挑战需要人工接管");
    }
    return state.targets().stream()
        .filter(this::eligibleSingleClickTarget)
        .filter(target -> SINGLE_CLICK.matcher(normalize(target.name())).find())
        .findFirst()
        .map(
            target ->
                new Classification(
                    "SINGLE_CLICK",
                    "EXPLICIT_SINGLE_CLICK_ACCESSIBILITY_SIGNAL",
                    "单次人机验证目标（" + safeRole(target.role()) + "）",
                    0.99,
                    target,
                    true))
        .orElse(null);
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

  private static Classification takeover(String type, String signal, String summary) {
    return new Classification(type, signal, summary, 0.98, null, false);
  }

  private static boolean automationEligible(String type) {
    return java.util.Set.of("SINGLE_CLICK", "IMAGE_SELECTION", "PUZZLE", "MULTI_ROUND")
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
      boolean oneClick) {}
}
