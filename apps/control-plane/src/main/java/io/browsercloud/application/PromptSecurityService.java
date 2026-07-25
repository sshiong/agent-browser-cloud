package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import io.browsercloud.api.CreateAgentTaskRequest.InstructionSourceRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Intent Guard、Instruction Firewall 和最小 Prompt Injection 检测器。 */
@Service
public final class PromptSecurityService {

  private static final Pattern FORBIDDEN_DIRECT =
      Pattern.compile(
          "(?iu)(cookie|浏览器缓存凭证|password|密码|otp|验证码|shell|命令行|raw\\s*cdp|"
              + "vault|kubernetes|k8s|node\\s*helper|关闭审计|disable\\s+audit|绕过策略|bypass\\s+policy)");
  private static final Pattern FINANCIAL =
      Pattern.compile("(?iu)(付款|支付|转账|购买|payment|transfer|purchase)");
  private static final Pattern ACCOUNT_OR_SECURITY =
      Pattern.compile(
          "(?iu)(删除账号|修改密码|安全设置|双因素|2fa|delete\\s+account|change\\s+password|security\\s+setting)");
  private static final Pattern DATA_CHANGE =
      Pattern.compile("(?iu)(提交|保存|更新|删除|发送|上传|submit|save|update|delete|send|upload)");
  private static final List<Pattern> INJECTION_SIGNALS =
      List.of(
          Pattern.compile("(?iu)(忽略|无视).{0,20}(之前|以上|系统).{0,12}(指令|提示|规则)"),
          Pattern.compile(
              "(?iu)ignore.{0,20}(previous|prior|system).{0,15}(instruction|prompt|rule)"),
          Pattern.compile("(?iu)(system\\s*message|系统消息|管理员已确认|admin(?:istrator)?\\s+approved)"),
          Pattern.compile("(?iu)(上传|发送|导出).{0,24}(cookie|密码|secret|密钥|token|所有文件)"),
          Pattern.compile("(?iu)(disable|关闭|绕过).{0,20}(audit|审计|policy|策略|security|安全)"),
          Pattern.compile("(?iu)(tool_call|function_call|调用工具|执行shell|execute\\s+shell)"),
          Pattern.compile("[\\u200B-\\u200F\\u2060\\uFEFF]"));
  private static final Set<InstructionSourceType> EXTERNAL_SOURCE_TYPES =
      Set.of(
          InstructionSourceType.APPLICATION_DATA,
          InstructionSourceType.EMAIL,
          InstructionSourceType.DOCUMENT,
          InstructionSourceType.WEB_CONTENT,
          InstructionSourceType.THIRD_PARTY_WIDGET);

  public IntentEvaluation evaluate(String goal, List<InstructionSourceRequest> requestedSources) {
    var now = Instant.now();
    var events = new ArrayList<SecurityEvent>();
    var sources = new ArrayList<InstructionSource>();
    var sanitizedGoal = AgentDataMinimizer.redact(goal).trim();
    var goalHash = sha256(goal);
    sources.add(
        new InstructionSource(
            "user_goal",
            InstructionSourceType.USER_REQUEST,
            TrustLevel.TRUSTED,
            "USER_INPUT",
            goalHash,
            true,
            List.of()));

    IntentDecision decision = IntentDecision.ALLOWED;
    RiskClass risk = RiskClass.R0_READ_ONLY;
    String reason = "";
    if (FORBIDDEN_DIRECT.matcher(goal).find()) {
      decision = IntentDecision.FORBIDDEN;
      risk = RiskClass.R5_SECURITY;
      reason = "DIRECT_PRIVILEGED_RESOURCE_REQUEST";
      events.add(
          event(
              "DIRECT_INJECTION",
              "HIGH",
              "BLOCK",
              reason,
              InstructionSourceType.USER_REQUEST,
              goalHash,
              now));
    } else if (FINANCIAL.matcher(goal).find()) {
      decision = IntentDecision.CONFIRM_REQUIRED;
      risk = RiskClass.R4_FINANCIAL;
      reason = "HIGH_RISK_CONFIRMATION_REQUIRED";
    } else if (ACCOUNT_OR_SECURITY.matcher(goal).find()) {
      decision = IntentDecision.CONFIRM_REQUIRED;
      risk = RiskClass.R3_ACCOUNT_CHANGE;
      reason = "HIGH_RISK_CONFIRMATION_REQUIRED";
    } else if (DATA_CHANGE.matcher(goal).find()) {
      risk = RiskClass.R2_DATA_CHANGE;
    }

    for (var requested :
        requestedSources == null ? List.<InstructionSourceRequest>of() : requestedSources) {
      var contentHash = sha256(requested.content());
      var sourceType = requested.sourceType();
      if (!EXTERNAL_SOURCE_TYPES.contains(sourceType)) {
        events.add(
            event(
                "SOURCE_AUTHORITY_SPOOF",
                "HIGH",
                "BLOCK",
                "CALLER_CANNOT_ASSERT_TRUSTED_SOURCE",
                sourceType,
                contentHash,
                now));
        decision = IntentDecision.FORBIDDEN;
        risk = RiskClass.R5_SECURITY;
        reason = "CALLER_CANNOT_ASSERT_TRUSTED_SOURCE";
      }
      var trust = trustFor(sourceType);
      var taints = new ArrayList<String>();
      if (trust == TrustLevel.UNTRUSTED) {
        taints.add("EXTERNAL_UNTRUSTED");
      }
      var injection =
          INJECTION_SIGNALS.stream()
              .anyMatch(pattern -> pattern.matcher(requested.content()).find());
      if (injection) {
        taints.add("PROMPT_INJECTION");
        events.add(
            event(
                "PROMPT_INJECTION_DETECTED",
                "HIGH",
                "QUARANTINE",
                "UNTRUSTED_INSTRUCTION_NOT_EXECUTABLE",
                sourceType,
                contentHash,
                now));
      }
      sources.add(
          new InstructionSource(
              requested.sourceId(),
              sourceType,
              trust,
              requested.classification().toUpperCase(Locale.ROOT),
              contentHash,
              false,
              List.copyOf(taints)));
    }

    return new IntentEvaluation(
        sanitizedGoal, decision, risk, reason, List.copyOf(sources), List.copyOf(events));
  }

  private static TrustLevel trustFor(InstructionSourceType sourceType) {
    return switch (sourceType) {
      case APPLICATION_DATA -> TrustLevel.RESTRICTED;
      case EMAIL, DOCUMENT, WEB_CONTENT, THIRD_PARTY_WIDGET -> TrustLevel.UNTRUSTED;
      default -> TrustLevel.TRUSTED;
    };
  }

  private static SecurityEvent event(
      String type,
      String severity,
      String decision,
      String rule,
      InstructionSourceType source,
      String contentHash,
      Instant now) {
    return new SecurityEvent(
        "sec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        type,
        severity,
        decision,
        rule,
        source,
        contentHash,
        now);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
