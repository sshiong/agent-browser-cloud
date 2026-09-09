package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentTaskMemoryServiceTest {

  @Test
  void resultMemoryContainsOnlyNormalizedHashesAndBoundedEvidence() {
    var jdbc = mock(JdbcTemplate.class);
    var descriptors =
        new AgentActionAttemptService(jdbc, new ObjectMapper().findAndRegisterModules());
    var memory = new AgentTaskMemoryService(jdbc, descriptors);
    var step = textStep("step_random", "capability-secret", "sealed-secret");
    var completedAt = Instant.parse("2026-09-09T00:00:00Z");
    var result =
        new ToolExecutionResult(
            step.stepId(),
            step.toolId(),
            "VERIFIED",
            "result-hash",
            java.util.Map.of("value", "bounded"),
            "VALUE_CHANGED",
            completedAt);

    memory.recordResult("tenant", "session", "task", "intent", 3, step, result);

    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(startsWith("INSERT INTO agent_task_memory_events"), arguments.capture());
    var persisted = Arrays.asList(arguments.getValue());
    assertThat(persisted).doesNotContain("sealed-secret", "capability-secret", "bounded");
    assertThat(persisted.get(10).toString()).matches("[a-f0-9]{64}");
    assertThat(persisted).contains("STEP_RESULT", "VERIFIED", "result-hash", "VALUE_CHANGED");
  }

  @Test
  void replanMemoryIsIdempotentlyKeyedAndDoesNotEmbedBrowserState() {
    var jdbc = mock(JdbcTemplate.class);
    var memory =
        new AgentTaskMemoryService(
            jdbc, new AgentActionAttemptService(jdbc, new ObjectMapper().findAndRegisterModules()));

    memory.recordReplan(
        "tenant",
        "session",
        "task",
        "intent",
        2,
        "STATE_STALE",
        41L,
        Instant.parse("2026-09-09T00:00:00Z"));

    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(startsWith("INSERT INTO agent_task_memory_events"), arguments.capture());
    var persisted = Arrays.asList(arguments.getValue());
    assertThat(persisted).contains("replan:2", "REPLAN", "STATE_STALE", 41L);
    assertThat(persisted).doesNotContain("state-json", "content-hash");
  }

  private static PlanStep textStep(String stepId, String capabilityId, String sealedPayload) {
    return new PlanStep(
        stepId,
        ToolId.TYPE_TEXT,
        RiskClass.R1_LOW_RISK_CHANGE,
        null,
        new StepInput(
            "target-save",
            9L,
            sealedPayload,
            "payload-hash",
            12,
            ActionDataClass.PUBLIC,
            null,
            null,
            null,
            false,
            1),
        "type",
        List.of("user_goal"),
        TrustLevel.TRUSTED,
        List.of(),
        false,
        ExecutionStrategy.SEMANTIC_DOM,
        "COMPLETE",
        "VALUE_CHANGED",
        capabilityId,
        "signed-" + capabilityId);
  }
}
