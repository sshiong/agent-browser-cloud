package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentInstructionAuthorityPolicyTest {

  @Test
  void acceptsOnlyStepsAuthorizedByTheCompleteTrustedSourceSet() {
    var plan = plan(step(List.of("user_goal", "platform_policy"), TrustLevel.TRUSTED, List.of()));

    assertThatCode(() -> AgentInstructionAuthorityPolicy.requireExecutablePlan(plan))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUntrustedRestrictedTaintedMissingOrDuplicateAuthorityBeforeDispatch() {
    var invalidSteps =
        List.of(
            step(List.of("user_goal", "page-1"), TrustLevel.UNTRUSTED, List.of()),
            step(List.of("user_goal", "platform_policy"), TrustLevel.RESTRICTED, List.of()),
            step(
                List.of("user_goal", "platform_policy"),
                TrustLevel.TRUSTED,
                List.of("NON_EXECUTABLE_CONTEXT")),
            step(List.of("user_goal"), TrustLevel.TRUSTED, List.of()),
            step(
                List.of("user_goal", "platform_policy", "USER_GOAL"),
                TrustLevel.TRUSTED,
                List.of()));

    for (var step : invalidSteps) {
      assertThatThrownBy(() -> AgentInstructionAuthorityPolicy.requireExecutablePlan(plan(step)))
          .isInstanceOf(AgentExecutionService.AgentExecutionRejectedException.class)
          .hasMessage("UNTRUSTED_INSTRUCTION_AUTHORITY");
    }
  }

  private static AgentPlan plan(PlanStep step) {
    return new AgentPlan("intent", List.of(step), 1, 0, Instant.now().plusSeconds(60));
  }

  private static PlanStep step(
      List<String> supportingSources, TrustLevel trustFloor, List<String> taints) {
    return new PlanStep(
        "step",
        ToolId.GET_CURRENT_STATE,
        RiskClass.R0_READ_ONLY,
        null,
        null,
        "read state",
        supportingSources,
        trustFloor,
        taints,
        false,
        ExecutionStrategy.SEMANTIC_DOM,
        "COMPLETE",
        "STATE_VERSION_PRESENT",
        "capability",
        "token");
  }
}
