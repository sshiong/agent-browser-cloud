package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AgentActionAttemptServiceTest {

  @Test
  void descriptorExcludesSecretsCapabilitiesAndEphemeralIds() {
    var service =
        new AgentActionAttemptService(
            mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules());
    var first = textStep("step_one", "cap-one", "sealed-one", 11L, "target-save", "payload-hash");
    var retry = textStep("step_two", "cap-two", "sealed-two", 99L, "target-save", "payload-hash");
    var otherTarget =
        textStep("step_three", "cap-three", "sealed-three", 99L, "target-cancel", "payload-hash");

    assertThat(service.descriptorHash(first)).isEqualTo(service.descriptorHash(retry));
    assertThat(service.descriptorHash(first)).isNotEqualTo(service.descriptorHash(otherTarget));
  }

  @Test
  @SuppressWarnings("unchecked")
  void thirdConsecutiveAttemptOnTheSameStateIsPersistedAndBlocked() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var service = new AgentActionAttemptService(jdbc, new ObjectMapper().findAndRegisterModules());
    var step = textStep("step_three", "cap-three", "sealed", 13L, "target-save", "payload-hash");
    var descriptor = service.descriptorHash(step);
    var signature = PromptSecurityService.sha256(descriptor + ":state-hash");
    var resultSet = mock(ResultSet.class);
    when(resultSet.getString("attempt_signature")).thenReturn(signature);
    when(resultSet.getInt("consecutive_count")).thenReturn(2);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("task"), eq("tenant")))
        .thenAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(resultSet, 0));
            });

    assertThatThrownBy(
            () ->
                service.reserve(
                    "tenant",
                    "session",
                    "task",
                    "operation",
                    step,
                    42,
                    "state-hash",
                    Instant.parse("2026-09-09T00:00:00Z")))
        .isInstanceOf(AgentActionAttemptService.ActionLoopDetectedException.class)
        .hasMessage("AGENT_ACTION_LOOP_DETECTED");

    verify(jdbc).update(startsWith("INSERT INTO agent_action_attempts"), any(Object[].class));
  }

  @Test
  void waitDoesNotCreateAnAttemptOrParticipateInLoopDetection() {
    var jdbc = mock(JdbcTemplate.class);
    var service = new AgentActionAttemptService(jdbc, new ObjectMapper().findAndRegisterModules());
    var step =
        new PlanStep(
            "step_wait",
            ToolId.WAIT_FOR,
            RiskClass.R0_READ_ONLY,
            null,
            new StepInput(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                WaitCondition.STATE_STABLE,
                1_000,
                false,
                1),
            "wait",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "COMPLETE",
            "STATE_STABLE",
            "cap-wait",
            "signed-wait");

    var reservation =
        service.reserve(
            "tenant", "session", "task", "operation", step, 42, "state-hash", Instant.now());

    assertThat(reservation.enabled()).isFalse();
    verifyNoInteractions(jdbc);
  }

  private static PlanStep textStep(
      String stepId,
      String capabilityId,
      String sealedPayload,
      long targetRevision,
      String targetRef,
      String payloadHash) {
    return new PlanStep(
        stepId,
        ToolId.TYPE_TEXT,
        RiskClass.R1_LOW_RISK_CHANGE,
        null,
        new StepInput(
            targetRef,
            targetRevision,
            sealedPayload,
            payloadHash,
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
