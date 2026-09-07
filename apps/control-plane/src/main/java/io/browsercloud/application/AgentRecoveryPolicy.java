package io.browsercloud.application;

import io.browsercloud.api.AgentTaskView.RecoveryGuidanceView;
import io.browsercloud.persistence.AgentTaskEntity;
import java.util.Locale;

/** One authoritative recovery instruction derived from durable Task state. */
final class AgentRecoveryPolicy {
  private AgentRecoveryPolicy() {}

  static RecoveryGuidanceView guidance(AgentTaskEntity task) {
    var state = task.getState();
    if ("COMPLETED".equals(state)) return null;
    if ("FAILED".equals(state)) {
      return guidance("TERMINAL", reason(task.getLastError(), "TASK_FAILED"), false);
    }
    if ("WAITING_FOR_HUMAN".equals(state) || "AWAITING_CONFIRMATION".equals(state)) {
      return guidance("HUMAN", reason(task.getBlockedReason(), "HUMAN_INPUT_REQUIRED"), false);
    }
    if ("PAUSED_BY_RESOURCE_POLICY".equals(state)) {
      return guidance("WAIT", "RESOURCE_POLICY_PAUSED", true);
    }
    if ("QUEUED".equals(state) || "AWAITING_REVIEW".equals(state)) {
      return guidance("WAIT", "WORKER_OR_REVIEW_PENDING", true);
    }
    if ("PLANNED".equals(state) && "FAILED".equals(task.getReviewerStatus())) {
      return guidance(
          "RETRY", reason(task.getReviewerFailureCode(), "REVIEW_RETRY_REQUIRED"), false);
    }
    if ("BLOCKED".equals(state)) {
      var blocked = reason(task.getBlockedReason(), "TASK_BLOCKED");
      if (containsAny(blocked, "FORBIDDEN", "PROMPT_INJECTION", "POLICY_DISABLED")) {
        return guidance("TERMINAL", blocked, false);
      }
      return guidance("HUMAN", blocked, false);
    }
    if ("RUNNING".equals(state) && task.getExecutionWaitReason() != null) {
      return guidance("WAIT", task.getExecutionWaitReason(), true);
    }
    if ("RUNNING".equals(state) && task.getReplanReason() != null) {
      var reason = task.getReplanReason();
      if (containsAny(reason, "STATE_NOT_ADVANCED", "STATE_NOT_EXECUTABLE", "STATE_CURSOR")) {
        return guidance("REFRESH", reason, true);
      }
      return guidance("REPLAN", reason, true);
    }
    return null;
  }

  private static RecoveryGuidanceView guidance(
      String directive, String reasonCode, boolean automatic) {
    return new RecoveryGuidanceView(directive, reasonCode, automatic);
  }

  private static String reason(String value, String fallback) {
    var normalized = value == null || value.isBlank() ? fallback : value.trim();
    return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
  }

  private static boolean containsAny(String value, String... fragments) {
    var upper = value.toUpperCase(Locale.ROOT);
    for (var fragment : fragments) {
      if (upper.contains(fragment)) return true;
    }
    return false;
  }
}
