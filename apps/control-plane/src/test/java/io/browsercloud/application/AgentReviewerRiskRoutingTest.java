package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.AgentReviewerModels.AgentReviewPayload;
import io.browsercloud.api.AgentReviewerModels.CompleteAgentReviewJobRequest;
import io.browsercloud.api.AgentReviewerModels.ReviewerDecision;
import io.browsercloud.domain.agent.AgentModels.AgentPlan;
import io.browsercloud.domain.agent.AgentModels.ExecutionStrategy;
import io.browsercloud.domain.agent.AgentModels.PlanStep;
import io.browsercloud.domain.agent.AgentModels.RiskClass;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import io.browsercloud.domain.agent.AgentModels.TrustLevel;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AgentReviewerRiskRoutingTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private AgentTaskJpaRepository tasks;
  @Mock private AgentExecutionWorkerApplicationService executionWorker;
  @Mock private AuditApplicationService audit;

  private ObjectMapper mapper;
  private AgentReviewerApplicationService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().findAndRegisterModules();
    service =
        new AgentReviewerApplicationService(
            jdbc,
            tasks,
            executionWorker,
            audit,
            mapper,
            true,
            90,
            3,
            new BigDecimal("0.80"),
            "reviewer-test-v1",
            "OPENAI_RESPONSES",
            "reviewer-test",
            "test-v1",
            "REDACTED_TASK_PLAN",
            512,
            0,
            0);
  }

  @Test
  void trustedLowRiskPlanBypassesModelReviewAndQueuesExecutorDirectly() {
    var task = task(RiskClass.R1_LOW_RISK_CHANGE, RiskClass.R1_LOW_RISK_CHANGE, List.of());
    when(executionWorker.enabled()).thenReturn(true);
    when(tasks.findForUpdate(task.getTaskId(), task.getTenantId())).thenReturn(Optional.of(task));

    service.routeForExecution(task.getTaskId(), task.getTenantId(), "idem-low-risk");

    assertThat(task.getReviewerStatus()).isEqualTo("NOT_REQUIRED");
    assertThat(task.getReviewerReasonCodes()).contains("DETERMINISTIC_LOW_RISK_BYPASS");
    assertThat(task.getReviewerCostMicros()).isNull();
    verify(executionWorker).enqueue(task.getTaskId(), task.getTenantId(), "idem-low-risk");
    // Enqueue acquires Session foreign-key locks before the serialized tenant audit head.
    // The reverse order deadlocks with a lifecycle transaction holding the Session row.
    var lockOrder = inOrder(executionWorker, audit);
    lockOrder
        .verify(executionWorker)
        .enqueue(task.getTaskId(), task.getTenantId(), "idem-low-risk");
    lockOrder.verify(audit).append(any(AuditApplicationService.AuditRecord.class));
    verify(tasks).save(task);
    verifyNoInteractions(jdbc);
  }

  @Test
  void dataChangeStillRequiresIndependentModelReview() {
    var task = task(RiskClass.R2_DATA_CHANGE, RiskClass.R2_DATA_CHANGE, List.of());

    assertThat(service.reviewRoutingDecision(task, Instant.now()))
        .isEqualTo(AgentReviewerApplicationService.ReviewRoutingDecision.MODEL_REVIEW_REQUIRED);
  }

  @Test
  void approvedReviewAcquiresExecutionParentLocksBeforeTheTenantAuditHead() throws Exception {
    var task = task(RiskClass.R2_DATA_CHANGE, RiskClass.R2_DATA_CHANGE, List.of());
    AgentReviewPayload payload = ReflectionTestUtils.invokeMethod(service, "reviewPayload", task);
    var token = "a".repeat(43);
    var values =
        Map.ofEntries(
            Map.entry("job_id", "rjob-fixture"),
            Map.entry("review_id", "rev-fixture"),
            Map.entry("task_id", task.getTaskId()),
            Map.entry("tenant_id", task.getTenantId()),
            Map.entry("session_id", task.getSessionId()),
            Map.entry("state", "EXECUTING"),
            Map.entry("worker_id", "review-worker"),
            Map.entry("claim_token_hash", hash(token)),
            Map.entry("execution_idempotency_key", "idem-approved"),
            Map.entry("deployment_id", "reviewer-test-v1"),
            Map.entry("model_revision", "test-v1"),
            Map.entry("plan_hash", payload.planHash()),
            Map.entry("reason_codes", "[]"),
            Map.entry("input_hash", hash(mapper.writeValueAsString(payload))));
    var row = mock(ResultSet.class);
    when(row.getString(anyString()))
        .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
    when(row.getTimestamp(anyString()))
        .thenAnswer(
            invocation ->
                "lease_expires_at".equals(invocation.getArgument(0))
                    ? Timestamp.from(Instant.now().plusSeconds(300))
                    : null);
    when(row.getInt(anyString()))
        .thenAnswer(
            invocation -> "maximum_output_tokens".equals(invocation.getArgument(0)) ? 512 : 0);
    when(jdbc.query(contains("WHERE job_id"), any(RowMapper.class), eq("rjob-fixture")))
        .thenAnswer(
            invocation -> {
              RowMapper<?> rowMapper = invocation.getArgument(1);
              return List.of(rowMapper.mapRow(row, 0));
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(tasks.findForUpdateByTaskId(task.getTaskId())).thenReturn(Optional.of(task));
    task.queueForReviewer("rev-fixture", Instant.now());

    service.complete(
        "rjob-fixture",
        new CompleteAgentReviewJobRequest(
            token,
            ReviewerDecision.APPROVE,
            List.of("SAFE"),
            BigDecimal.ONE,
            "reviewer-test-v1",
            "test-v1",
            "fixture-provider-request",
            10,
            10,
            1,
            "b".repeat(64)),
        "review-worker");

    assertThat(task.getReviewerStatus()).isEqualTo("APPROVED");
    var lockOrder = inOrder(executionWorker, audit);
    lockOrder
        .verify(executionWorker)
        .enqueue(task.getTaskId(), task.getTenantId(), "idem-approved");
    lockOrder.verify(audit).append(any(AuditApplicationService.AuditRecord.class));
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void underclassifiedOrTaintedPlanFailsClosedToModelReview() {
    var underclassified = task(RiskClass.R1_LOW_RISK_CHANGE, RiskClass.R2_DATA_CHANGE, List.of());
    var tainted =
        task(RiskClass.R1_LOW_RISK_CHANGE, RiskClass.R1_LOW_RISK_CHANGE, List.of("WEB_CONTENT"));

    assertThat(service.reviewRoutingDecision(underclassified, Instant.now()))
        .isEqualTo(AgentReviewerApplicationService.ReviewRoutingDecision.MODEL_REVIEW_REQUIRED);
    assertThat(service.reviewRoutingDecision(tainted, Instant.now()))
        .isEqualTo(AgentReviewerApplicationService.ReviewRoutingDecision.MODEL_REVIEW_REQUIRED);
  }

  private AgentTaskEntity task(RiskClass taskRisk, RiskClass stepRisk, List<String> taintLabels) {
    var now = Instant.now();
    var tool =
        stepRisk.ordinal() >= RiskClass.R2_DATA_CHANGE.ordinal()
            ? ToolId.TYPE_TEXT
            : ToolId.CLICK_TARGET;
    var step =
        new PlanStep(
            "step_1234567890abcdef",
            tool,
            stepRisk,
            null,
            null,
            "bounded action",
            List.of("user_goal", "platform_policy"),
            TrustLevel.TRUSTED,
            taintLabels,
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "COMPLETE",
            "STATE_VERSION_PRESENT",
            "cap_1234567890abcdef",
            "opaque-token");
    var plan = new AgentPlan("intent_1234567890abcdef", List.of(step), 4, 1, now.plusSeconds(300));
    try {
      return new AgentTaskEntity(
          "agt_1234567890abcdef",
          "tenant-test",
          "ses_1234567890abcdef",
          "Perform the bounded action",
          "PLANNED",
          taskRisk.name(),
          "ALLOWED",
          null,
          AgentPolicy.BALANCED,
          mapper.writeValueAsString(List.of("example.test")),
          mapper.writeValueAsString(plan),
          "[]",
          now);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
