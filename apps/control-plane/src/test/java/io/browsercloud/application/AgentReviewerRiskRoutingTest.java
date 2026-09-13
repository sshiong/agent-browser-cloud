package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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
