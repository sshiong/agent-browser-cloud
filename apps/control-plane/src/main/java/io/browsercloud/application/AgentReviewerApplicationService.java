package io.browsercloud.application;

import static io.browsercloud.api.AgentReviewerModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.domain.agent.AgentModels.AgentPlan;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fenced Reviewer Agent queue and model-governance boundary.
 *
 * <p>The Reviewer receives a capability-free plan summary. It never receives sealed field values,
 * page state, raw context sources, customer credentials, or executable commands. A model approval
 * is advisory until this service independently verifies the exact plan hash and policy envelope.
 */
@Service
public class AgentReviewerApplicationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> REVIEW_REASON_CODES =
      Set.of(
          "SAFE",
          "EXCESSIVE_SCOPE",
          "DOMAIN_MISMATCH",
          "RISK_UNDERCLASSIFIED",
          "MISSING_CONFIRMATION",
          "UNSUPPORTED_TOOL",
          "DATA_POLICY_VIOLATION",
          "PROMPT_INJECTION_RISK",
          "MODEL_UNCERTAIN");

  private final JdbcTemplate jdbc;
  private final AgentTaskJpaRepository tasks;
  private final AgentExecutionWorkerApplicationService executionWorker;
  private final AuditApplicationService auditService;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final Duration claimLease;
  private final int maximumAttempts;
  private final BigDecimal minimumConfidence;
  private final ModelDeployment deployment;

  public AgentReviewerApplicationService(
      JdbcTemplate jdbc,
      AgentTaskJpaRepository tasks,
      AgentExecutionWorkerApplicationService executionWorker,
      AuditApplicationService auditService,
      ObjectMapper objectMapper,
      @Value("${agent.reviewer.external.enabled:false}") boolean enabled,
      @Value("${agent.reviewer.external.claim-lease-seconds:90}") long claimLeaseSeconds,
      @Value("${agent.reviewer.external.maximum-attempts:3}") int maximumAttempts,
      @Value("${agent.reviewer.minimum-confidence:0.80}") BigDecimal minimumConfidence,
      @Value("${agent.reviewer.deployment-id:reviewer-local-v1}") String deploymentId,
      @Value("${agent.reviewer.provider-type:OPENAI_RESPONSES}") String providerType,
      @Value("${agent.reviewer.model-name:reviewer-local}") String modelName,
      @Value("${agent.reviewer.model-revision:local-v1}") String modelRevision,
      @Value("${agent.reviewer.data-policy:REDACTED_TASK_PLAN}") String dataPolicy,
      @Value("${agent.reviewer.maximum-output-tokens:512}") int maximumOutputTokens,
      @Value("${agent.reviewer.input-price-micros-per-million-tokens:0}") long inputPriceMicros,
      @Value("${agent.reviewer.output-price-micros-per-million-tokens:0}") long outputPriceMicros) {
    if (claimLeaseSeconds < 30 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "agent.reviewer.external.claim-lease-seconds must be between 30 and 300");
    }
    if (maximumAttempts < 1 || maximumAttempts > 10) {
      throw new IllegalArgumentException(
          "agent.reviewer.external.maximum-attempts must be between 1 and 10");
    }
    if (minimumConfidence.compareTo(BigDecimal.ZERO) < 0
        || minimumConfidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("agent.reviewer.minimum-confidence must be in [0,1]");
    }
    if (!providerType.equals("OPENAI_RESPONSES")) {
      throw new IllegalArgumentException("unsupported Reviewer model provider type");
    }
    if (maximumOutputTokens < 64 || maximumOutputTokens > 4096) {
      throw new IllegalArgumentException("Reviewer maximum output tokens must be 64..4096");
    }
    if (inputPriceMicros < 0 || outputPriceMicros < 0) {
      throw new IllegalArgumentException("Reviewer model prices cannot be negative");
    }
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.executionWorker = executionWorker;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
    this.maximumAttempts = maximumAttempts;
    this.minimumConfidence = minimumConfidence;
    this.deployment =
        new ModelDeployment(
            safeIdentifier(deploymentId, 128),
            providerType,
            safeIdentifier(modelName, 200),
            safeIdentifier(modelRevision, 200),
            safeIdentifier(dataPolicy, 128),
            maximumOutputTokens,
            inputPriceMicros,
            outputPriceMicros);
  }

  public boolean enabled() {
    return enabled;
  }

  /** Queue one exact plan for independent review before any execution job becomes visible. */
  @Transactional
  public void enqueueForExecution(String taskId, String tenantId, String idempotencyKey) {
    if (!enabled) {
      throw new AgentReviewRejectedException("AGENT_REVIEWER_NOT_ENABLED");
    }
    if (!executionWorker.enabled()) {
      throw new AgentReviewRejectedException("AGENT_REVIEWER_REQUIRES_EXTERNAL_EXECUTION_WORKER");
    }
    var task = requireTask(taskId, tenantId);
    if (isTerminal(task) || TaskState.QUEUED.name().equals(task.getState())) {
      return;
    }
    if (TaskState.AWAITING_REVIEW.name().equals(task.getState())) {
      return;
    }
    if (!TaskState.PLANNED.name().equals(task.getState())) {
      throw new AgentReviewRejectedException("AGENT_TASK_NOT_PLANNED");
    }
    var payload = reviewPayload(task);
    var planHash = payload.planHash();
    if ("APPROVED".equals(task.getReviewerStatus())
        && planHash.equals(task.getReviewedPlanHash())) {
      executionWorker.enqueue(taskId, tenantId, idempotencyKey);
      return;
    }
    var now = Instant.now();
    var reviewId = id("rev_");
    var jobId = id("rjob_");
    var inputHash = sha256(write(payload));
    var existing = findByTaskId(taskId);
    if (existing.isPresent()) {
      var job = existing.orElseThrow();
      if (!Set.of("FAILED", "REJECTED").contains(job.state()) || !job.planHash().equals(planHash)) {
        throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_ALREADY_EXISTS");
      }
      reviewId = job.reviewId();
      jobId = job.jobId();
      jdbc.update(
          """
          UPDATE agent_review_jobs
             SET execution_idempotency_key = ?, plan_hash = ?, state = 'QUEUED', attempt = 0,
                 maximum_attempts = ?, worker_id = NULL, claim_token_hash = NULL,
                 lease_expires_at = NULL, available_at = ?, deployment_id = ?,
                 provider_type = ?, model_name = ?, model_revision = ?, data_policy = ?,
                 maximum_output_tokens = ?, input_price_micros_per_mtok = ?,
                 output_price_micros_per_mtok = ?,
                 decision = NULL, reason_codes = '[]', confidence = NULL, input_hash = ?,
                 output_hash = NULL, provider_request_id = NULL, input_tokens = NULL,
                 output_tokens = NULL, cost_micros = NULL, latency_ms = NULL,
                 started_at = NULL, completed_at = NULL, failure_code = NULL, updated_at = ?
           WHERE job_id = ? AND state IN ('FAILED', 'REJECTED')
          """,
          idempotencyKey,
          planHash,
          maximumAttempts,
          sqlTime(now),
          deployment.deploymentId(),
          deployment.providerType(),
          deployment.modelName(),
          deployment.modelRevision(),
          deployment.dataPolicy(),
          deployment.maximumOutputTokens(),
          deployment.inputPriceMicros(),
          deployment.outputPriceMicros(),
          inputHash,
          sqlTime(now),
          jobId);
    } else {
      jdbc.update(
          """
          INSERT INTO agent_review_jobs(
            job_id, review_id, task_id, tenant_id, session_id, execution_idempotency_key,
            protocol_version, plan_hash, state, attempt, maximum_attempts, claim_epoch,
            available_at, deployment_id, provider_type, model_name, model_revision, data_policy,
            maximum_output_tokens,
            input_price_micros_per_mtok, output_price_micros_per_mtok, input_hash,
            created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, 'reviewer-worker/v1', ?, 'QUEUED', 0, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          jobId,
          reviewId,
          taskId,
          tenantId,
          task.getSessionId(),
          idempotencyKey,
          planHash,
          maximumAttempts,
          sqlTime(now),
          deployment.deploymentId(),
          deployment.providerType(),
          deployment.modelName(),
          deployment.modelRevision(),
          deployment.dataPolicy(),
          deployment.maximumOutputTokens(),
          deployment.inputPriceMicros(),
          deployment.outputPriceMicros(),
          inputHash,
          sqlTime(now),
          sqlTime(now));
    }
    appendEvent(jobId, "ENQUEUED", "QUEUED", 0, null, 0, null, null, now);
    task.queueForReviewer(reviewId, now);
    tasks.save(task);
    appendAudit(task, reviewId, "AGENT_REVIEW_QUEUED", "QUEUED", Map.of("planHash", planHash));
  }

  @Transactional
  public Optional<AgentReviewJobClaimView> claim(
      ClaimAgentReviewJobRequest request, String workerId) {
    if (!Boolean.TRUE.equals(request.capabilities().get("openai-responses-v1"))) {
      throw new AgentReviewRejectedException("REVIEWER_WORKER_CAPABILITY_MISSING");
    }
    if (!deployment.deploymentId().equals(request.deploymentId())
        || !deployment.modelRevision().equals(request.modelRevision())) {
      throw new AgentReviewRejectedException("REVIEWER_MODEL_DEPLOYMENT_MISMATCH");
    }
    var now = Instant.now();
    reapExpired(now, 50);
    var candidates =
        jdbc.queryForList(
            """
            SELECT job_id
              FROM agent_review_jobs
             WHERE state = 'QUEUED' AND available_at <= ? AND attempt < maximum_attempts
               AND deployment_id = ? AND model_revision = ?
             ORDER BY available_at, created_at, job_id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            request.deploymentId(),
            request.modelRevision());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    var jobId = candidates.getFirst();
    var token = token();
    var leaseExpiresAt = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE agent_review_jobs
               SET state = 'CLAIMED', attempt = attempt + 1, worker_id = ?,
                   claim_epoch = claim_epoch + 1, claim_token_hash = ?, lease_expires_at = ?,
                   failure_code = NULL, updated_at = ?
             WHERE job_id = ? AND state = 'QUEUED' AND attempt < maximum_attempts
            """,
            workerId,
            sha256(token),
            sqlTime(leaseExpiresAt),
            sqlTime(now),
            jobId);
    if (changed != 1) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_CLAIM_FENCED");
    }
    var job = requireJob(jobId);
    var task = requireTaskById(job.taskId());
    var payload = reviewPayload(task);
    if (!job.planHash().equals(payload.planHash())
        || !job.inputHash().equals(sha256(write(payload)))) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_PLAN_CHANGED");
    }
    appendEvent(
        jobId, "CLAIMED", "CLAIMED", job.attempt(), workerId, job.claimEpoch(), null, null, now);
    return Optional.of(
        new AgentReviewJobClaimView(token, toView(job), payload, leaseExpiresAt, job.claimEpoch()));
  }

  @Transactional
  public AgentReviewJobView start(
      String jobId, AgentReviewJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "CLAIMED");
    var changed =
        jdbc.update(
            """
            UPDATE agent_review_jobs
               SET state = 'EXECUTING', started_at = COALESCE(started_at, ?), updated_at = ?
             WHERE job_id = ? AND state = 'CLAIMED' AND claim_epoch = ?
            """,
            sqlTime(now),
            sqlTime(now),
            jobId,
            job.claimEpoch());
    if (changed != 1) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_START_FENCED");
    }
    var task = requireTaskById(job.taskId());
    task.markReviewerRunning(now);
    tasks.save(task);
    appendEvent(
        jobId, "STARTED", "EXECUTING", job.attempt(), workerId, job.claimEpoch(), null, null, now);
    return toView(requireJob(jobId));
  }

  @Transactional
  public AgentReviewJobView heartbeat(
      String jobId, AgentReviewJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    var changed =
        jdbc.update(
            """
            UPDATE agent_review_jobs
               SET lease_expires_at = ?, updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            sqlTime(now.plus(claimLease)),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_HEARTBEAT_FENCED");
    }
    appendEvent(
        jobId,
        "HEARTBEAT",
        "EXECUTING",
        job.attempt(),
        workerId,
        job.claimEpoch(),
        null,
        null,
        now);
    return toView(requireJob(jobId));
  }

  @Transactional
  public AgentReviewJobView complete(
      String jobId, CompleteAgentReviewJobRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    if (!job.deploymentId().equals(request.deploymentId())
        || !job.modelRevision().equals(request.modelRevision())) {
      throw new AgentReviewRejectedException("REVIEWER_MODEL_DEPLOYMENT_MISMATCH");
    }
    if (request.outputTokens() > job.maximumOutputTokens()) {
      throw new AgentReviewRejectedException("REVIEWER_MODEL_OUTPUT_BUDGET_EXCEEDED");
    }
    var outcome =
        policyDecision(
            request.decision(), normalizedReasons(request.reasonCodes()), request.confidence());
    var reasons = outcome.reasonCodes();
    var decision = outcome.decision();
    var task = requireTaskById(job.taskId());
    var payload = reviewPayload(task);
    if (!job.planHash().equals(payload.planHash())
        || !job.inputHash().equals(sha256(write(payload)))) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_PLAN_CHANGED");
    }
    var costMicros =
        tokenCost(request.inputTokens(), job.inputPriceMicros())
            + tokenCost(request.outputTokens(), job.outputPriceMicros());
    var terminalState = decision == ReviewerDecision.APPROVE ? "APPROVED" : "REJECTED";
    var changed =
        jdbc.update(
            """
            UPDATE agent_review_jobs
               SET state = ?, decision = ?, reason_codes = CAST(? AS jsonb), confidence = ?,
                   output_hash = ?, provider_request_id = ?, input_tokens = ?, output_tokens = ?,
                   cost_micros = ?, latency_ms = ?, completed_at = ?, worker_id = NULL,
                   claim_token_hash = NULL, lease_expires_at = NULL, failure_code = NULL,
                   updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            terminalState,
            decision.name(),
            write(reasons),
            request.confidence(),
            request.outputHash(),
            request.providerRequestId(),
            request.inputTokens(),
            request.outputTokens(),
            costMicros,
            request.latencyMs(),
            sqlTime(now),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_COMPLETE_FENCED");
    }
    task.recordReviewerAccounting(
        job.deploymentId(),
        job.modelName(),
        job.modelRevision(),
        request.inputTokens(),
        request.outputTokens(),
        costMicros,
        request.latencyMs());
    if (decision == ReviewerDecision.APPROVE) {
      task.approveReviewer(job.planHash(), write(reasons), now);
    } else {
      task.rejectReviewer(job.planHash(), write(reasons), now);
    }
    tasks.save(task);
    appendEvent(
        jobId,
        terminalState,
        terminalState,
        job.attempt(),
        workerId,
        job.claimEpoch(),
        decision.name(),
        null,
        now);
    appendAudit(
        task,
        job.reviewId(),
        "AGENT_REVIEW_COMPLETED",
        decision.name(),
        Map.of(
            "planHash", job.planHash(),
            "deploymentId", job.deploymentId(),
            "modelRevision", job.modelRevision(),
            "inputTokens", request.inputTokens(),
            "outputTokens", request.outputTokens(),
            "costMicros", costMicros,
            "reasonCodes", reasons));
    if (decision == ReviewerDecision.APPROVE) {
      executionWorker.enqueue(job.taskId(), job.tenantId(), job.executionIdempotencyKey());
    }
    return toView(requireJob(jobId));
  }

  @Transactional
  public AgentReviewJobView fail(String jobId, FailAgentReviewJobRequest request, String workerId) {
    var now = Instant.now();
    var job = requireActiveClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    var retry = request.retryable() && job.attempt() < job.maximumAttempts();
    var nextState = retry ? "QUEUED" : "FAILED";
    var completedAt = retry ? null : sqlTime(now);
    var changed =
        jdbc.update(
            """
            UPDATE agent_review_jobs
               SET state = ?, available_at = ?, completed_at = ?, failure_code = ?,
                   worker_id = NULL, claim_token_hash = NULL, lease_expires_at = NULL,
                   updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            nextState,
            sqlTime(retry ? now.plusSeconds(backoffSeconds(job.attempt())) : now),
            completedAt,
            request.failureCode(),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_FAIL_FENCED");
    }
    var task = requireTaskById(job.taskId());
    if (retry) {
      task.requeueReviewer(now);
      appendEvent(
          jobId,
          "REQUEUED",
          "QUEUED",
          job.attempt(),
          workerId,
          job.claimEpoch(),
          null,
          request.failureCode(),
          now);
    } else {
      task.failReviewer(request.failureCode(), now);
      appendEvent(
          jobId,
          "FAILED",
          "FAILED",
          job.attempt(),
          workerId,
          job.claimEpoch(),
          null,
          request.failureCode(),
          now);
    }
    tasks.save(task);
    return toView(requireJob(jobId));
  }

  @Scheduled(fixedDelayString = "${agent.reviewer.external.reaper-interval-ms:15000}")
  @Transactional
  public void reapExpiredClaims() {
    if (enabled) {
      reapExpired(Instant.now(), 100);
    }
  }

  void reapExpired(Instant now, int limit) {
    var ids =
        jdbc.queryForList(
            """
            SELECT job_id
              FROM agent_review_jobs
             WHERE state IN ('CLAIMED', 'EXECUTING') AND lease_expires_at <= ?
             ORDER BY lease_expires_at, job_id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            Math.max(1, Math.min(limit, 500)));
    for (var jobId : ids) {
      var job = requireJob(jobId);
      var retry = job.attempt() < job.maximumAttempts();
      var state = retry ? "QUEUED" : "FAILED";
      jdbc.update(
          """
          UPDATE agent_review_jobs
             SET state = ?, available_at = ?, completed_at = ?, failure_code = 'REVIEWER_LEASE_EXPIRED',
                 worker_id = NULL, claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
           WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING') AND claim_epoch = ?
          """,
          state,
          sqlTime(retry ? now.plusSeconds(backoffSeconds(job.attempt())) : now),
          retry ? null : sqlTime(now),
          sqlTime(now),
          jobId,
          job.claimEpoch());
      var task = requireTaskById(job.taskId());
      if (retry) {
        task.requeueReviewer(now);
        appendEvent(
            jobId,
            "REQUEUED",
            "QUEUED",
            job.attempt(),
            job.workerId(),
            job.claimEpoch(),
            null,
            "REVIEWER_LEASE_EXPIRED",
            now);
      } else {
        task.failReviewer("REVIEWER_LEASE_EXPIRED", now);
        appendEvent(
            jobId,
            "FAILED",
            "FAILED",
            job.attempt(),
            job.workerId(),
            job.claimEpoch(),
            null,
            "REVIEWER_LEASE_EXPIRED",
            now);
      }
      tasks.save(task);
    }
  }

  private AgentReviewPayload reviewPayload(AgentTaskEntity task) {
    var plan = read(task.getPlan(), AgentPlan.class);
    var allowedDomains = read(task.getAllowedDomains(), new TypeReference<List<String>>() {});
    var steps =
        plan.steps().stream()
            .map(
                step ->
                    new AgentReviewStepView(
                        step.stepId(),
                        step.toolId(),
                        step.riskClass(),
                        targetOrigin(step.targetUrl()),
                        step.input() == null || step.input().targetRef() == null
                            ? null
                            : sha256(step.input().targetRef()),
                        step.input() == null || step.input().dataClass() == null
                            ? null
                            : step.input().dataClass().name(),
                        step.input() == null ? null : step.input().payloadLength(),
                        step.input() == null ? 0 : step.input().actions().size(),
                        step.input() == null || step.input().actions().isEmpty()
                            ? null
                            : sha256(
                                write(
                                    step.input().actions().stream()
                                        .map(
                                            action ->
                                                java.util.Map.of(
                                                    "actionId",
                                                    action.actionId(),
                                                    "toolId",
                                                    action.toolId().name(),
                                                    "targetRefHash",
                                                    action.targetRef() == null
                                                        ? ""
                                                        : sha256(action.targetRef()),
                                                    "dataClass",
                                                    action.dataClass() == null
                                                        ? ""
                                                        : action.dataClass().name(),
                                                    "payloadLength",
                                                    action.payloadLength() == null
                                                        ? 0
                                                        : action.payloadLength(),
                                                    "sensitiveTargetAuthorized",
                                                    action.allowSensitiveTarget()))
                                        .toList())),
                        step.requiredConfirmation(),
                        step.strategy(),
                        step.requiredStateQuality(),
                        step.verification()))
            .toList();
    var fingerprint = new LinkedHashMap<String, Object>();
    fingerprint.put("taskId", task.getTaskId());
    fingerprint.put("riskClass", task.getRiskClass());
    fingerprint.put("allowedDomains", allowedDomains);
    fingerprint.put("maximumActions", plan.maxActions());
    fingerprint.put("replanBudget", plan.replanBudget());
    fingerprint.put("steps", steps);
    var planHash = sha256(write(fingerprint));
    return new AgentReviewPayload(
        task.getTaskId(),
        AgentDataMinimizer.redact(task.getGoal()),
        io.browsercloud.domain.agent.AgentModels.RiskClass.valueOf(task.getRiskClass()),
        allowedDomains,
        plan.maxActions(),
        plan.replanBudget(),
        steps,
        planHash,
        deployment.dataPolicy());
  }

  private ReviewOutcome policyDecision(
      ReviewerDecision requested, List<String> reasons, BigDecimal confidence) {
    if (requested == ReviewerDecision.APPROVE) {
      if (confidence.compareTo(minimumConfidence) < 0) {
        return new ReviewOutcome(ReviewerDecision.REJECT, List.of("MODEL_UNCERTAIN"));
      }
      if (!reasons.equals(List.of("SAFE"))) {
        throw new AgentReviewRejectedException("REVIEWER_APPROVAL_REASON_INVALID");
      }
    } else if (reasons.contains("SAFE")) {
      throw new AgentReviewRejectedException("REVIEWER_REJECTION_REASON_INVALID");
    }
    return new ReviewOutcome(requested, reasons);
  }

  private List<String> normalizedReasons(List<String> values) {
    var reasons = new LinkedHashSet<String>();
    for (var value : values) {
      var normalized = value.trim().toUpperCase(Locale.ROOT);
      if (!REVIEW_REASON_CODES.contains(normalized)) {
        throw new AgentReviewRejectedException("REVIEWER_REASON_CODE_UNSUPPORTED");
      }
      reasons.add(normalized);
    }
    if (reasons.isEmpty()) {
      throw new AgentReviewRejectedException("REVIEWER_REASON_CODE_REQUIRED");
    }
    return List.copyOf(reasons);
  }

  private AgentReviewJob requireActiveClaim(
      String jobId, String claimToken, String workerId, Instant now, String requiredState) {
    var job = requireJob(jobId);
    if (!requiredState.equals(job.state())) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_STATE_INVALID");
    }
    if (!workerId.equals(job.workerId())
        || !MessageDigest.isEqual(
            sha256(claimToken).getBytes(StandardCharsets.US_ASCII),
            job.claimTokenHash().getBytes(StandardCharsets.US_ASCII))) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_CLAIM_TOKEN_INVALID");
    }
    if (job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
      throw new AgentReviewRejectedException("AGENT_REVIEW_JOB_LEASE_EXPIRED");
    }
    return job;
  }

  private AgentTaskEntity requireTask(String taskId, String tenantId) {
    return tasks
        .findForUpdate(taskId, tenantId)
        .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
  }

  private AgentTaskEntity requireTaskById(String taskId) {
    return tasks
        .findForUpdateByTaskId(taskId)
        .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
  }

  private Optional<AgentReviewJob> findByTaskId(String taskId) {
    return jdbc
        .query("SELECT * FROM agent_review_jobs WHERE task_id = ?", this::mapJob, taskId)
        .stream()
        .findFirst();
  }

  private AgentReviewJob requireJob(String jobId) {
    return jdbc
        .query("SELECT * FROM agent_review_jobs WHERE job_id = ?", this::mapJob, jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AgentReviewRejectedException("AGENT_REVIEW_JOB_NOT_FOUND"));
  }

  private AgentReviewJob mapJob(ResultSet result, int row) throws SQLException {
    return new AgentReviewJob(
        result.getString("job_id"),
        result.getString("review_id"),
        result.getString("task_id"),
        result.getString("tenant_id"),
        result.getString("session_id"),
        result.getString("execution_idempotency_key"),
        result.getString("protocol_version"),
        result.getString("plan_hash"),
        result.getString("state"),
        result.getInt("attempt"),
        result.getInt("maximum_attempts"),
        result.getString("worker_id"),
        result.getLong("claim_epoch"),
        result.getString("claim_token_hash"),
        instant(result, "lease_expires_at"),
        instant(result, "available_at"),
        result.getString("deployment_id"),
        result.getString("provider_type"),
        result.getString("model_name"),
        result.getString("model_revision"),
        result.getString("data_policy"),
        result.getInt("maximum_output_tokens"),
        result.getLong("input_price_micros_per_mtok"),
        result.getLong("output_price_micros_per_mtok"),
        result.getString("decision"),
        read(result.getString("reason_codes"), new TypeReference<List<String>>() {}),
        result.getBigDecimal("confidence"),
        result.getString("input_hash"),
        result.getString("output_hash"),
        result.getString("provider_request_id"),
        integer(result, "input_tokens"),
        integer(result, "output_tokens"),
        longValue(result, "cost_micros"),
        integer(result, "latency_ms"),
        instant(result, "started_at"),
        instant(result, "completed_at"),
        result.getString("failure_code"),
        instant(result, "updated_at"));
  }

  private AgentReviewJobView toView(AgentReviewJob job) {
    return new AgentReviewJobView(
        job.jobId(),
        job.reviewId(),
        job.taskId(),
        job.protocolVersion(),
        job.state(),
        job.attempt(),
        job.maximumAttempts(),
        job.workerId(),
        job.claimEpoch(),
        job.leaseExpiresAt(),
        job.availableAt(),
        new ReviewerModelDeploymentView(
            job.deploymentId(),
            job.providerType(),
            job.modelName(),
            job.modelRevision(),
            job.dataPolicy(),
            job.maximumOutputTokens()),
        job.decision() == null ? null : ReviewerDecision.valueOf(job.decision()),
        job.reasonCodes(),
        job.confidence(),
        job.inputHash(),
        job.outputHash(),
        job.providerRequestId(),
        job.inputTokens(),
        job.outputTokens(),
        job.costMicros(),
        job.latencyMs(),
        job.startedAt(),
        job.completedAt(),
        job.failureCode(),
        job.updatedAt());
  }

  private void appendEvent(
      String jobId,
      String eventType,
      String state,
      int attempt,
      String workerId,
      long claimEpoch,
      String decision,
      String failureCode,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO agent_review_job_events(
          job_id, event_type, state, attempt, worker_id, claim_epoch, decision,
          failure_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        jobId,
        eventType,
        state,
        attempt,
        workerId,
        claimEpoch,
        decision,
        failureCode,
        sqlTime(now));
  }

  private void appendAudit(
      AgentTaskEntity task,
      String reviewId,
      String action,
      String outcome,
      Map<String, Object> details) {
    auditService.append(
        new AuditApplicationService.AuditRecord(
            task.getTenantId(),
            task.getSessionId(),
            action,
            "SYSTEM",
            "agent-reviewer-governance",
            "AGENT_REVIEW",
            reviewId,
            action,
            outcome,
            details,
            UUID.randomUUID().toString()));
  }

  private static boolean isTerminal(AgentTaskEntity task) {
    return Set.of("BLOCKED", "RUNNING", "WAITING_FOR_HUMAN", "COMPLETED", "FAILED")
        .contains(task.getState());
  }

  private static long tokenCost(int tokens, long priceMicrosPerMillionTokens) {
    return BigDecimal.valueOf(tokens)
        .multiply(BigDecimal.valueOf(priceMicrosPerMillionTokens))
        .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING)
        .longValueExact();
  }

  private static long backoffSeconds(int attempt) {
    return Math.min(300, 5L << Math.min(6, Math.max(0, attempt - 1)));
  }

  private static String targetOrigin(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      var uri = URI.create(value);
      if (uri.getHost() == null || uri.getUserInfo() != null) return null;
      var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      if (!Set.of("http", "https").contains(scheme)) return null;
      var host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      var port = uri.getPort();
      return scheme + "://" + host + (port < 0 ? "" : ":" + port);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static String safeIdentifier(String value, int maximumLength) {
    var normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()
        || normalized.length() > maximumLength
        || !normalized.matches("^[A-Za-z0-9._:/-]+$")) {
      throw new IllegalArgumentException("Reviewer model identifier is invalid");
    }
    return normalized;
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static String token() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize Agent review evidence", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent review evidence", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent review evidence", exception);
    }
  }

  private static Timestamp sqlTime(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    var value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Integer integer(ResultSet result, String column) throws SQLException {
    var value = result.getInt(column);
    return result.wasNull() ? null : value;
  }

  private static Long longValue(ResultSet result, String column) throws SQLException {
    var value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  private record ModelDeployment(
      String deploymentId,
      String providerType,
      String modelName,
      String modelRevision,
      String dataPolicy,
      int maximumOutputTokens,
      long inputPriceMicros,
      long outputPriceMicros) {}

  private record ReviewOutcome(ReviewerDecision decision, List<String> reasonCodes) {}

  private record AgentReviewJob(
      String jobId,
      String reviewId,
      String taskId,
      String tenantId,
      String sessionId,
      String executionIdempotencyKey,
      String protocolVersion,
      String planHash,
      String state,
      int attempt,
      int maximumAttempts,
      String workerId,
      long claimEpoch,
      String claimTokenHash,
      Instant leaseExpiresAt,
      Instant availableAt,
      String deploymentId,
      String providerType,
      String modelName,
      String modelRevision,
      String dataPolicy,
      int maximumOutputTokens,
      long inputPriceMicros,
      long outputPriceMicros,
      String decision,
      List<String> reasonCodes,
      BigDecimal confidence,
      String inputHash,
      String outputHash,
      String providerRequestId,
      Integer inputTokens,
      Integer outputTokens,
      Long costMicros,
      Integer latencyMs,
      Instant startedAt,
      Instant completedAt,
      String failureCode,
      Instant updatedAt) {}

  public static class AgentReviewRejectedException extends RuntimeException {
    public AgentReviewRejectedException(String reason) {
      super(reason);
    }
  }
}
