package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed authority boundary between trusted instructions and external page/application data.
 */
final class AgentInstructionAuthorityPolicy {

  static final String USER_GOAL_SOURCE = "user_goal";
  static final String PLATFORM_POLICY_SOURCE = "platform_policy";
  static final String NON_EXECUTABLE_CONTEXT_TAINT = "NON_EXECUTABLE_CONTEXT";
  static final String INVALID_PLAN_AUTHORITY = "UNTRUSTED_INSTRUCTION_AUTHORITY";

  private static final Set<String> EXECUTABLE_SOURCE_IDS =
      Set.of(USER_GOAL_SOURCE, PLATFORM_POLICY_SOURCE);

  private AgentInstructionAuthorityPolicy() {}

  static boolean isReservedSourceId(String sourceId) {
    return sourceId != null
        && EXECUTABLE_SOURCE_IDS.contains(sourceId.trim().toLowerCase(Locale.ROOT));
  }

  static String canonicalSourceId(String sourceId) {
    return sourceId == null ? "" : sourceId.trim().toLowerCase(Locale.ROOT);
  }

  static void requireExecutablePlan(AgentPlan plan) {
    if (plan == null || plan.steps() == null) {
      throw new AgentExecutionService.AgentExecutionRejectedException(INVALID_PLAN_AUTHORITY);
    }
    for (var step : plan.steps()) {
      if (!hasTrustedInstructionAuthority(step)) {
        throw new AgentExecutionService.AgentExecutionRejectedException(INVALID_PLAN_AUTHORITY);
      }
    }
  }

  static boolean hasTrustedInstructionAuthority(PlanStep step) {
    if (step == null
        || step.supportingSources() == null
        || step.trustFloor() != TrustLevel.TRUSTED
        || step.taintLabels() == null
        || !step.taintLabels().isEmpty()) {
      return false;
    }
    var canonicalSources = new HashSet<String>();
    for (var sourceId : step.supportingSources()) {
      var canonical = canonicalSourceId(sourceId);
      if (!EXECUTABLE_SOURCE_IDS.contains(canonical) || !canonicalSources.add(canonical)) {
        return false;
      }
    }
    return canonicalSources.equals(EXECUTABLE_SOURCE_IDS);
  }
}
