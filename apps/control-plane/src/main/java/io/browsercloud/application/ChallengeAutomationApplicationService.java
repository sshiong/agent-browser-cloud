package io.browsercloud.application;

import static io.browsercloud.api.ChallengeAutomationModels.*;
import static io.browsercloud.api.SessionEvidenceModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Durable screenshot/OCR/vision Challenge automation with a bounded action budget. */
@Service
public class ChallengeAutomationApplicationService {

  private static final String SYSTEM_ACTOR = "challenge-automation";
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(45);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final java.util.Set<String> AUTOMATABLE_TYPES =
      java.util.Set.of("SINGLE_CLICK", "IMAGE_SELECTION", "PUZZLE", "MULTI_ROUND");

  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final AgentTaskJpaRepository tasks;
  private final ChallengeEventJpaRepository challenges;
  private final SessionEvidenceGovernanceService evidence;
  private final BrowserStateRepository states;
  private final OperationRepository operations;
  private final NodeCommandGateway commands;
  private final AgentExecutionService agentExecution;
  private final AuditApplicationService audit;
  private final ObjectMapper objectMapper;
  private final String deploymentId;
  private final String modelRevision;

  public ChallengeAutomationApplicationService(
      JdbcTemplate jdbc,
      SessionRepository sessions,
      AgentTaskJpaRepository tasks,
      ChallengeEventJpaRepository challenges,
      SessionEvidenceGovernanceService evidence,
      BrowserStateRepository states,
      OperationRepository operations,
      NodeCommandGateway commands,
      AgentExecutionService agentExecution,
      AuditApplicationService audit,
      ObjectMapper objectMapper,
      @Value("${challenge-vision.deployment-id:challenge-vision-default}") String deploymentId,
      @Value("${challenge-vision.model-revision:challenge-vision-v1}") String modelRevision) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.tasks = tasks;
    this.challenges = challenges;
    this.evidence = evidence;
    this.states = states;
    this.operations = operations;
    this.commands = commands;
    this.agentExecution = agentExecution;
    this.audit = audit;
    this.objectMapper = objectMapper;
    this.deploymentId = deploymentId;
    this.modelRevision = modelRevision;
  }

  @Transactional(readOnly = true)
  public ChallengeAutomationPolicyView policy(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return jdbc.queryForObject(
        """
        SELECT id, agent_control_mode, agent_sensitive_input_max_attempts,
               challenge_automation_enabled, challenge_automation_max_attempts,
               challenge_automation_min_confidence, challenge_automation_allow_multi_click,
               challenge_automation_allow_slide, challenge_motion_min_steps,
               challenge_motion_max_steps, challenge_motion_min_delay_ms,
               challenge_motion_max_delay_ms, challenge_target_offset_ratio, updated_at
        FROM sessions WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
        """,
        (result, row) -> policy(result),
        sessionId,
        tenantId);
  }

  @Transactional
  public ChallengeAutomationPolicyView updatePolicy(
      String sessionId,
      String tenantId,
      String actorId,
      UpdateChallengeAutomationPolicyRequest request,
      String requestId) {
    requireTenant(sessionId, tenantId);
    if (request.enabled() && request.maximumAttempts() == 0) {
      throw new IllegalArgumentException("Enabled Challenge automation needs at least one attempt");
    }
    if (request.motionMaximumSteps() < request.motionMinimumSteps()
        || request.motionMaximumDelayMs() < request.motionMinimumDelayMs()) {
      throw new IllegalArgumentException("Challenge motion bounds are invalid");
    }
    var current = policy(sessionId, tenantId);
    var requestedControlMode =
        request.controlMode() == null ? current.controlMode() : request.controlMode();
    var requestedSensitiveAttempts =
        request.sensitiveInputMaximumAttempts() == null
            ? current.sensitiveInputMaximumAttempts()
            : request.sensitiveInputMaximumAttempts();
    if (current.enabled() == request.enabled()
        && current.controlMode() == requestedControlMode
        && current.sensitiveInputMaximumAttempts() == requestedSensitiveAttempts
        && current.maximumAttempts() == request.maximumAttempts()
        && current.minimumConfidence().compareTo(request.minimumConfidence()) == 0
        && current.allowMultiClick() == request.allowMultiClick()
        && current.allowSlide() == request.allowSlide()
        && current.motionMinimumSteps() == request.motionMinimumSteps()
        && current.motionMaximumSteps() == request.motionMaximumSteps()
        && current.motionMinimumDelayMs() == request.motionMinimumDelayMs()
        && current.motionMaximumDelayMs() == request.motionMaximumDelayMs()
        && current.targetOffsetRatio().compareTo(request.targetOffsetRatio()) == 0) {
      return current;
    }
    jdbc.update(
        """
        UPDATE sessions
        SET agent_control_mode = ?, agent_sensitive_input_max_attempts = ?,
            challenge_automation_enabled = ?, challenge_automation_max_attempts = ?,
            challenge_automation_min_confidence = ?,
            challenge_automation_allow_multi_click = ?, challenge_automation_allow_slide = ?,
            challenge_motion_min_steps = ?, challenge_motion_max_steps = ?,
            challenge_motion_min_delay_ms = ?, challenge_motion_max_delay_ms = ?,
            challenge_target_offset_ratio = ?,
            updated_at = now()
        WHERE id = ? AND tenant_id = ?
        """,
        requestedControlMode.name(),
        requestedSensitiveAttempts,
        request.enabled(),
        request.maximumAttempts(),
        request.minimumConfidence(),
        request.allowMultiClick(),
        request.allowSlide(),
        request.motionMinimumSteps(),
        request.motionMaximumSteps(),
        request.motionMinimumDelayMs(),
        request.motionMaximumDelayMs(),
        request.targetOffsetRatio(),
        sessionId,
        tenantId);
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "AGENT_CHALLENGE_AUTOMATION_POLICY_UPDATED",
            "USER",
            actorId,
            "SESSION",
            sessionId,
            "UPDATE",
            "COMMITTED",
            Map.ofEntries(
                Map.entry("controlMode", requestedControlMode.name()),
                Map.entry("sensitiveInputMaximumAttempts", requestedSensitiveAttempts),
                Map.entry("enabled", request.enabled()),
                Map.entry("maximumAttempts", request.maximumAttempts()),
                Map.entry("allowMultiClick", request.allowMultiClick()),
                Map.entry("allowSlide", request.allowSlide()),
                Map.entry("motionMinimumSteps", request.motionMinimumSteps()),
                Map.entry("motionMaximumSteps", request.motionMaximumSteps()),
                Map.entry("motionMinimumDelayMs", request.motionMinimumDelayMs()),
                Map.entry("motionMaximumDelayMs", request.motionMaximumDelayMs()),
                Map.entry("targetOffsetRatio", request.targetOffsetRatio())),
            requestId));
    return policy(sessionId, tenantId);
  }

  @Transactional(readOnly = true)
  public Optional<ChallengeAutomationRunView> currentRun(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return jdbc
        .query(
            """
            SELECT * FROM challenge_automation_runs
            WHERE tenant_id = ? AND session_id = ?
            ORDER BY created_at DESC, run_id DESC LIMIT 1
            """,
            (result, row) -> runView(result),
            tenantId,
            sessionId)
        .stream()
        .findFirst();
  }

  /** Called only after the triggering Agent step has committed and the task is durably paused. */
  @Transactional
  public void challengeObserved(String challengeEventId, String tenantId, String sessionId) {
    var challenge = challenges.findForUpdate(challengeEventId, tenantId).orElse(null);
    if (challenge == null) return;
    var task =
        tasks
            .findWaitingForChallengeBySessionForUpdate(sessionId, tenantId, PageRequest.of(0, 1))
            .stream()
            .findFirst()
            .orElse(null);
    if (task == null || !TaskState.WAITING_FOR_HUMAN.name().equals(task.getState())) return;
    if (activeRun(task.getTaskId(), tenantId).isPresent()) return;
    var policy = policy(sessionId, tenantId);
    if (!AUTOMATABLE_TYPES.contains(challenge.getSuspectedType())) {
      if (policy.controlMode()
          == io.browsercloud.domain.agent.AgentModels.AgentControlMode.AUTONOMOUS) {
        agentExecution.requestHumanAssistance(
            challengeEventId,
            tenantId,
            "AUTONOMOUS_CHALLENGE_" + challenge.getSuspectedType() + "_UNRESOLVED");
        appendAudit(
            tenantId,
            sessionId,
            task.getTaskId(),
            "AGENT_AUTONOMOUS_CHALLENGE_RESULT",
            "NEEDS_HUMAN",
            Map.of("challengeType", challenge.getSuspectedType(), "humanTakeoverRequired", false));
      }
      return;
    }
    if (!policy.enabled() || policy.maximumAttempts() == 0) {
      if (policy.controlMode()
          == io.browsercloud.domain.agent.AgentModels.AgentControlMode.AUTONOMOUS) {
        agentExecution.requestHumanAssistance(
            challengeEventId, tenantId, "AUTONOMOUS_CHALLENGE_AUTOMATION_DISABLED");
        appendAudit(
            tenantId,
            sessionId,
            task.getTaskId(),
            "AGENT_AUTONOMOUS_CHALLENGE_RESULT",
            "NEEDS_HUMAN",
            Map.of("challengeType", challenge.getSuspectedType(), "automationEnabled", false));
      }
      return;
    }
    var now = Instant.now();
    var runId = id("car_");
    jdbc.update(
        """
        INSERT INTO challenge_automation_runs(
            run_id, tenant_id, session_id, task_id, current_challenge_event_id, state,
            attempt_count, maximum_attempts, minimum_confidence, allow_multi_click,
            allow_slide, motion_min_steps, motion_max_steps, motion_min_delay_ms,
            motion_max_delay_ms, target_offset_ratio, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'CAPTURING', 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        runId,
        tenantId,
        sessionId,
        task.getTaskId(),
        challengeEventId,
        policy.maximumAttempts(),
        policy.minimumConfidence(),
        policy.allowMultiClick(),
        policy.allowSlide(),
        policy.motionMinimumSteps(),
        policy.motionMaximumSteps(),
        policy.motionMinimumDelayMs(),
        policy.motionMaximumDelayMs(),
        policy.targetOffsetRatio(),
        Timestamp.from(now),
        Timestamp.from(now));
    appendAudit(
        tenantId,
        sessionId,
        runId,
        "AGENT_CHALLENGE_AUTOMATION_STARTED",
        "ACCEPTED",
        Map.of(
            "maximumAttempts",
            policy.maximumAttempts(),
            "challengeType",
            challenge.getSuspectedType()));
    scheduleCapture(requireRun(runId), challengeEventId);
  }

  /** Binds an asynchronously committed, redacted screenshot to its durable vision job. */
  @Transactional
  public void evidenceCaptured(
      String tenantId, io.browsercloud.coordinator.NodeEvent.EvidenceCaptured captured) {
    var job =
        jdbc
            .query(
                """
                SELECT job.* FROM challenge_visual_jobs job
                JOIN session_evidence_capture_requests capture ON capture.capture_id = job.capture_id
                WHERE job.tenant_id = ? AND capture.command_id = ? AND job.state = 'CAPTURING'
                FOR UPDATE OF job
                """,
                this::job,
                tenantId,
                captured.commandId())
            .stream()
            .findFirst()
            .orElse(null);
    if (job == null) return;
    if (!"COMMITTED".equals(captured.result())) {
      failAttempt(job, safeCode(captured.errorCode(), "CHALLENGE_SCREENSHOT_FAILED"));
      return;
    }
    jdbc.update(
        """
        UPDATE challenge_visual_jobs
        SET evidence_id = ?, state = 'READY', available_at = now(), updated_at = now(), version = version + 1
        WHERE job_id = ? AND state = 'CAPTURING'
        """,
        captured.evidenceId(),
        job.jobId());
    jdbc.update(
        "UPDATE challenge_automation_runs SET state = 'ANALYZING', updated_at = now(), version = version + 1 WHERE run_id = ?",
        job.runId());
  }

  @Transactional
  public Optional<ChallengeVisualJobClaimView> claim(
      ClaimChallengeVisualJobRequest request, String workerId) {
    if (!Boolean.TRUE.equals(request.capabilities().get("screenshot-ocr-actions-v1"))) {
      throw new ChallengeAutomationRejectedException("VISION_WORKER_CAPABILITY_MISSING");
    }
    if (!deploymentId.equals(request.deploymentId())
        || !modelRevision.equals(request.modelRevision())) {
      throw new ChallengeAutomationRejectedException("VISION_MODEL_REVISION_MISMATCH");
    }
    var token = token();
    var claimed =
        claimReadyJob(workerId, sha256(token), request.deploymentId(), request.modelRevision());
    if (claimed.isEmpty()) return Optional.empty();
    var job = claimed.orElseThrow();
    try {
      var grant =
          evidence.createAccessGrant(
              job.sessionId(),
              job.evidenceId(),
              job.tenantId(),
              workerId,
              "vision-grant-" + job.jobId(),
              job.jobId(),
              new CreateEvidenceAccessGrantRequest(EvidencePurpose.CHANGE_VALIDATION));
      var redeemed =
          evidence.redeem(job.sessionId(), grant.grantId(), job.tenantId(), workerId, job.jobId());
      var run = requireRun(job.runId());
      var challenge =
          challenges.findForUpdate(job.challengeEventId(), job.tenantId()).orElseThrow();
      return Optional.of(
          new ChallengeVisualJobClaimView(
              token,
              view(job, run),
              redeemed.downloadUrl(),
              redeemed.expiresAt(),
              challenge.getSuspectedType(),
              challenge.getTargetSummary(),
              run.allowMultiClick(),
              run.allowSlide(),
              run.minimumConfidence()));
    } catch (RuntimeException exception) {
      throw exception;
    }
  }

  @Transactional
  public ChallengeVisualJobView start(
      String jobId, ChallengeVisualJobClaimRequest request, String workerId) {
    var job = requireClaim(jobId, request.claimToken(), workerId, "CLAIMED");
    jdbc.update(
        "UPDATE challenge_visual_jobs SET state='RUNNING', started_at=now(), updated_at=now(), version=version+1 WHERE job_id=?",
        jobId);
    return view(requireJob(jobId), requireRun(job.runId()));
  }

  @Transactional
  public ChallengeVisualJobView heartbeat(
      String jobId, ChallengeVisualJobClaimRequest request, String workerId) {
    var job = requireClaim(jobId, request.claimToken(), workerId, "RUNNING");
    jdbc.update(
        "UPDATE challenge_visual_jobs SET lease_expires_at=?, updated_at=now(), version=version+1 WHERE job_id=?",
        Timestamp.from(Instant.now().plus(CLAIM_LEASE)),
        jobId);
    return view(requireJob(jobId), requireRun(job.runId()));
  }

  @Transactional
  public ChallengeVisualJobView complete(
      String jobId, CompleteChallengeVisualJobRequest request, String workerId) {
    var job = requireClaim(jobId, request.claimToken(), workerId, "RUNNING");
    var run = requireRun(job.runId());
    if (!deploymentId.equals(request.deploymentId())
        || !modelRevision.equals(request.modelRevision())) {
      throw new ChallengeAutomationRejectedException("VISION_MODEL_REVISION_MISMATCH");
    }
    validateDecision(request, run);
    jdbc.update(
        """
        UPDATE challenge_visual_jobs
        SET decision=?, actions=?::jsonb, confidence=?, provider_request_id=?, input_tokens=?,
            output_tokens=?, latency_ms=?, output_hash=?, updated_at=now(), version=version+1
        WHERE job_id=?
        """,
        request.decision().name(),
        json(request.actions()),
        request.confidence(),
        request.providerRequestId(),
        request.inputTokens(),
        request.outputTokens(),
        request.latencyMs(),
        request.outputHash(),
        jobId);
    if (request.decision() == VisualDecision.ESCALATE
        || request.confidence().compareTo(run.minimumConfidence()) < 0) {
      escalate(
          run,
          job,
          request.decision() == VisualDecision.ESCALATE ? "MODEL_ESCALATED" : "LOW_CONFIDENCE");
      return view(requireJob(jobId), requireRun(run.runId()));
    }
    var session = sessions.requireForUpdate(job.sessionId());
    if (!job.tenantId().equals(session.tenantId()))
      throw new SessionNotFoundException(job.sessionId());
    operations.ensureNoActiveOperation(job.sessionId());
    var snapshot =
        states
            .find(job.sessionId())
            .filter(value -> value.tenantId().equals(job.tenantId()))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(
                () -> new ChallengeAutomationRejectedException("CURRENT_STATE_UNAVAILABLE"));
    var operation =
        OperationFactory.challengeAutomation(
            session, run.runId(), operations.nextOperationEpoch(session.sessionId()));
    operations.insert(operation);
    jdbc.update(
        "UPDATE challenge_visual_jobs SET state='EXECUTING', operation_id=?, updated_at=now(), version=version+1 WHERE job_id=?",
        operation.operationId(),
        jobId);
    jdbc.update(
        "UPDATE challenge_automation_runs SET state='EXECUTING', last_action=?, updated_at=now(), version=version+1 WHERE run_id=?",
        actionSummary(request.actions()),
        run.runId());
    commands.send(
        NodeCommands.challengeAutomationAction(
            session,
            operation,
            run.runId(),
            job.jobId(),
            job.challengeEventId(),
            job.attemptNumber(),
            snapshot.state().stateVersion(),
            snapshot.state().stateHash(),
            request.actions(),
            run.motionMinimumSteps(),
            run.motionMaximumSteps(),
            run.motionMinimumDelayMs(),
            run.motionMaximumDelayMs(),
            run.targetOffsetRatio()));
    return view(requireJob(jobId), requireRun(run.runId()));
  }

  @Transactional
  public ChallengeVisualJobView fail(
      String jobId, FailChallengeVisualJobRequest request, String workerId) {
    var job = requireClaim(jobId, request.claimToken(), workerId, "RUNNING", "CLAIMED");
    if (request.retryable()) {
      failAttempt(job, request.failureCode());
    } else {
      var run = requireRun(job.runId());
      escalate(run, job, request.failureCode());
    }
    return view(requireJob(jobId), requireRun(job.runId()));
  }

  /** Commits one Node action and either recaptures the next round or resumes the Agent. */
  @Transactional
  public void stateUpdated(
      io.browsercloud.coordinator.NodeEventReceived envelope,
      io.browsercloud.coordinator.NodeEvent.StateUpdated state,
      String nextChallengeEventId) {
    if (!"CHALLENGE_AUTOMATION".equals(state.snapshotKind()) || envelope.operationEpoch() == 0)
      return;
    var job = findExecutingJob(state.requestedRootRef(), envelope.tenantId()).orElse(null);
    if (job == null) return;
    var operation =
        operations
            .findActive(envelope.sessionId())
            .filter(value -> value.operationEpoch() == envelope.operationEpoch())
            .filter(value -> value.mode() == OperationMode.CHALLENGE_AUTOMATION)
            .filter(value -> value.operationId().equals(job.operationId()))
            .orElseThrow(
                () -> new ChallengeAutomationRejectedException("STALE_CHALLENGE_AUTOMATION"));
    operations.transitionPhase(
        operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
    operations.transition(operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
    jdbc.update(
        "UPDATE challenge_visual_jobs SET state='COMPLETED', completed_at=now(), updated_at=now(), version=version+1 WHERE job_id=?",
        job.jobId());
    var run = requireRun(job.runId());
    if (nextChallengeEventId == null) {
      jdbc.update(
          "UPDATE challenge_automation_runs SET state='COMPLETED', completed_at=now(), updated_at=now(), version=version+1 WHERE run_id=?",
          run.runId());
      appendAudit(
          run.tenantId(),
          run.sessionId(),
          run.runId(),
          "AGENT_CHALLENGE_AUTOMATION_COMPLETED",
          "COMMITTED",
          Map.of("attemptCount", run.attemptCount()));
      agentExecution.resumeAfterHumanAssist(run.currentChallengeEventId(), run.tenantId());
      return;
    }
    jdbc.update(
        "UPDATE challenge_automation_runs SET current_challenge_event_id=?, updated_at=now(), version=version+1 WHERE run_id=?",
        nextChallengeEventId,
        run.runId());
    if (run.attemptCount() >= run.maximumAttempts()) {
      exhaust(requireRun(run.runId()), "ATTEMPT_BUDGET_EXHAUSTED");
    } else {
      scheduleCapture(requireRun(run.runId()), nextChallengeEventId);
    }
  }

  @Transactional
  public void failed(
      io.browsercloud.coordinator.NodeEventReceived envelope,
      io.browsercloud.coordinator.NodeEvent.ChallengeAutomationFailed failure) {
    var job = findExecutingJob(failure.jobId(), envelope.tenantId()).orElse(null);
    if (job == null || !job.runId().equals(failure.runId())) return;
    operations
        .findActive(envelope.sessionId())
        .filter(value -> value.operationEpoch() == envelope.operationEpoch())
        .filter(value -> value.mode() == OperationMode.CHALLENGE_AUTOMATION)
        .ifPresent(
            value ->
                operations.transition(
                    value.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    failAttempt(job, failure.errorCode());
  }

  @Scheduled(fixedDelayString = "${challenge-vision.lease-scan-interval-ms:15000}")
  @Transactional
  public void recoverExpiredClaims() {
    var now = Timestamp.from(Instant.now());
    var expired =
        jdbc.query(
            """
            SELECT * FROM challenge_visual_jobs
            WHERE state IN ('CLAIMED','RUNNING') AND lease_expires_at <= ?
            ORDER BY lease_expires_at, job_id LIMIT 100 FOR UPDATE SKIP LOCKED
            """,
            this::job,
            now);
    for (var job : expired) {
      if (job.claimEpoch() < 3) {
        jdbc.update(
            """
            UPDATE challenge_visual_jobs
            SET state='READY', worker_id=NULL, claim_token_hash=NULL, lease_expires_at=NULL,
                available_at=now(), failure_code='VISION_WORKER_LEASE_EXPIRED', updated_at=now(), version=version+1
            WHERE job_id=?
            """,
            job.jobId());
      } else {
        failAttempt(job, "VISION_WORKER_RETRY_EXHAUSTED");
      }
    }
  }

  private void scheduleCapture(Run run, String challengeEventId) {
    var nextAttempt = run.attemptCount() + 1;
    if (nextAttempt > run.maximumAttempts()) {
      exhaust(run, "ATTEMPT_BUDGET_EXHAUSTED");
      return;
    }
    var capture =
        evidence.capture(
            run.sessionId(),
            run.tenantId(),
            SYSTEM_ACTOR,
            "challenge-capture-" + run.runId() + "-" + nextAttempt,
            run.runId(),
            new CaptureEvidenceRequest(EvidencePurpose.CHANGE_VALIDATION));
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO challenge_visual_jobs(
            job_id, run_id, tenant_id, session_id, challenge_event_id, attempt_number,
            capture_id, state, available_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'CAPTURING', ?, ?, ?)
        """,
        id("cvj_"),
        run.runId(),
        run.tenantId(),
        run.sessionId(),
        challengeEventId,
        nextAttempt,
        capture.captureId(),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        """
        UPDATE challenge_automation_runs
        SET state='CAPTURING', attempt_count=?, current_challenge_event_id=?, updated_at=now(), version=version+1
        WHERE run_id=?
        """,
        nextAttempt,
        challengeEventId,
        run.runId());
  }

  private void failAttempt(Job job, String code) {
    jdbc.update(
        """
        UPDATE challenge_visual_jobs
        SET state='FAILED', failure_code=?, completed_at=now(), updated_at=now(), version=version+1
        WHERE job_id=? AND state NOT IN ('COMPLETED','FAILED','ESCALATED')
        """,
        safeCode(code, "CHALLENGE_AUTOMATION_FAILED"),
        job.jobId());
    var run = requireRun(job.runId());
    if (run.attemptCount() >= run.maximumAttempts()) {
      exhaust(run, safeCode(code, "ATTEMPT_BUDGET_EXHAUSTED"));
    } else {
      scheduleCapture(run, run.currentChallengeEventId());
    }
  }

  private void exhaust(Run run, String code) {
    finishRun(run, "EXHAUSTED", code);
  }

  private void escalate(Run run, Job job, String code) {
    jdbc.update(
        "UPDATE challenge_visual_jobs SET state='ESCALATED', failure_code=?, completed_at=now(), updated_at=now(), version=version+1 WHERE job_id=?",
        code,
        job.jobId());
    finishRun(run, "ESCALATED", code);
  }

  private void finishRun(Run run, String state, String code) {
    jdbc.update(
        """
        UPDATE challenge_automation_runs
        SET state=?, last_error_code=?, completed_at=now(), updated_at=now(), version=version+1
        WHERE run_id=? AND state NOT IN ('COMPLETED','EXHAUSTED','ESCALATED','FAILED')
        """,
        state,
        safeCode(code, "CHALLENGE_AUTOMATION_FAILED"),
        run.runId());
    appendAudit(
        run.tenantId(),
        run.sessionId(),
        run.runId(),
        "AGENT_CHALLENGE_AUTOMATION_RESULT",
        "NEEDS_HUMAN",
        Map.of(
            "attemptCount",
            run.attemptCount(),
            "reason",
            safeCode(code, "CHALLENGE_AUTOMATION_FAILED")));
    if (policy(run.sessionId(), run.tenantId()).controlMode()
        == io.browsercloud.domain.agent.AgentModels.AgentControlMode.AUTONOMOUS) {
      agentExecution.requestHumanAssistance(
          run.currentChallengeEventId(),
          run.tenantId(),
          "AUTONOMOUS_CHALLENGE_" + safeCode(code, "AUTOMATION_FAILED"));
    }
  }

  private Optional<Job> claimReadyJob(
      String workerId, String tokenHash, String deployment, String revision) {
    return jdbc
        .query(
            """
            WITH selected AS (
              SELECT job_id FROM challenge_visual_jobs
              WHERE state='READY' AND available_at <= now()
              ORDER BY available_at, created_at, job_id
              LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE challenge_visual_jobs AS job
            SET state='CLAIMED', worker_id=?, claim_token_hash=?, claim_epoch=claim_epoch+1,
                lease_expires_at=?, model_deployment_id=?, model_revision=?,
                updated_at=now(), version=version+1
            FROM selected
            WHERE job.job_id=selected.job_id AND job.state='READY'
            RETURNING job.*
            """,
            this::job,
            workerId,
            tokenHash,
            Timestamp.from(Instant.now().plus(CLAIM_LEASE)),
            deployment,
            revision)
        .stream()
        .findFirst();
  }

  private Job requireClaim(String jobId, String claimToken, String workerId, String... states) {
    var job = requireJob(jobId);
    if (!java.util.Set.of(states).contains(job.state())
        || !workerId.equals(job.workerId())
        || job.leaseExpiresAt() == null
        || !job.leaseExpiresAt().isAfter(Instant.now())
        || !sha256(claimToken).equals(job.claimTokenHash())) {
      throw new ChallengeAutomationRejectedException("VISION_JOB_CLAIM_INVALID");
    }
    return job;
  }

  private void validateDecision(CompleteChallengeVisualJobRequest request, Run run) {
    if (request.decision() == VisualDecision.ESCALATE) {
      if (!request.actions().isEmpty()) {
        throw new ChallengeAutomationRejectedException("ESCALATION_CANNOT_CONTAIN_ACTIONS");
      }
      return;
    }
    if (request.actions().isEmpty()) {
      throw new ChallengeAutomationRejectedException("VISUAL_ACTIONS_REQUIRED");
    }
    var totalInteractions = 0;
    for (var action : request.actions()) {
      totalInteractions += action.repeatCount();
      if (action.actionType() == VisualActionType.SLIDE) {
        if (!run.allowSlide()
            || action.endX() == null
            || action.endY() == null
            || action.repeatCount() != 1) {
          throw new ChallengeAutomationRejectedException("SLIDE_NOT_ALLOWED");
        }
      } else {
        if (action.endX() != null || action.endY() != null) {
          throw new ChallengeAutomationRejectedException("CLICK_ENDPOINT_FORBIDDEN");
        }
      }
    }
    if (totalInteractions > 1 && !run.allowMultiClick()) {
      throw new ChallengeAutomationRejectedException("MULTI_CLICK_NOT_ALLOWED");
    }
    if (totalInteractions > 8) {
      throw new ChallengeAutomationRejectedException("ACTION_BUDGET_EXCEEDED");
    }
  }

  private Optional<Run> activeRun(String taskId, String tenantId) {
    return jdbc
        .query(
            """
            SELECT * FROM challenge_automation_runs
            WHERE tenant_id=? AND task_id=? AND state IN ('CAPTURING','ANALYZING','EXECUTING')
            FOR UPDATE
            """,
            this::run,
            tenantId,
            taskId)
        .stream()
        .findFirst();
  }

  private Run requireRun(String runId) {
    return jdbc
        .query("SELECT * FROM challenge_automation_runs WHERE run_id=?", this::run, runId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ChallengeAutomationRejectedException("CHALLENGE_AUTOMATION_RUN_NOT_FOUND"));
  }

  private Job requireJob(String jobId) {
    return jdbc
        .query("SELECT * FROM challenge_visual_jobs WHERE job_id=?", this::job, jobId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ChallengeAutomationRejectedException("CHALLENGE_VISUAL_JOB_NOT_FOUND"));
  }

  private Optional<Job> findExecutingJob(String jobId, String tenantId) {
    return jdbc
        .query(
            "SELECT * FROM challenge_visual_jobs WHERE job_id=? AND tenant_id=? AND state='EXECUTING' FOR UPDATE",
            this::job,
            jobId,
            tenantId)
        .stream()
        .findFirst();
  }

  private ChallengeAutomationPolicyView policy(ResultSet result) throws SQLException {
    return new ChallengeAutomationPolicyView(
        result.getString("id"),
        io.browsercloud.domain.agent.AgentModels.AgentControlMode.valueOf(
            result.getString("agent_control_mode")),
        result.getInt("agent_sensitive_input_max_attempts"),
        result.getBoolean("challenge_automation_enabled"),
        result.getInt("challenge_automation_max_attempts"),
        result.getBigDecimal("challenge_automation_min_confidence"),
        result.getBoolean("challenge_automation_allow_multi_click"),
        result.getBoolean("challenge_automation_allow_slide"),
        result.getInt("challenge_motion_min_steps"),
        result.getInt("challenge_motion_max_steps"),
        result.getInt("challenge_motion_min_delay_ms"),
        result.getInt("challenge_motion_max_delay_ms"),
        result.getBigDecimal("challenge_target_offset_ratio"),
        result.getTimestamp("updated_at").toInstant());
  }

  private Run run(ResultSet result, int row) throws SQLException {
    return new Run(
        result.getString("run_id"),
        result.getString("tenant_id"),
        result.getString("session_id"),
        result.getString("task_id"),
        result.getString("current_challenge_event_id"),
        result.getString("state"),
        result.getInt("attempt_count"),
        result.getInt("maximum_attempts"),
        result.getBigDecimal("minimum_confidence"),
        result.getBoolean("allow_multi_click"),
        result.getBoolean("allow_slide"),
        result.getInt("motion_min_steps"),
        result.getInt("motion_max_steps"),
        result.getInt("motion_min_delay_ms"),
        result.getInt("motion_max_delay_ms"),
        result.getBigDecimal("target_offset_ratio"),
        result.getString("last_action"),
        result.getString("last_error_code"),
        instant(result, "updated_at"),
        instant(result, "completed_at"));
  }

  private Job job(ResultSet result, int row) throws SQLException {
    return new Job(
        result.getString("job_id"),
        result.getString("run_id"),
        result.getString("tenant_id"),
        result.getString("session_id"),
        result.getString("challenge_event_id"),
        result.getInt("attempt_number"),
        result.getString("evidence_id"),
        result.getString("state"),
        result.getString("worker_id"),
        result.getString("claim_token_hash"),
        result.getLong("claim_epoch"),
        instant(result, "lease_expires_at"),
        result.getString("operation_id"),
        result.getString("decision"),
        result.getString("actions"),
        result.getBigDecimal("confidence"),
        result.getString("failure_code"),
        instant(result, "updated_at"));
  }

  private ChallengeAutomationRunView runView(ResultSet result) throws SQLException {
    var run = run(result, 0);
    return new ChallengeAutomationRunView(
        run.runId(),
        run.currentChallengeEventId(),
        run.state(),
        run.attemptCount(),
        run.maximumAttempts(),
        run.lastAction(),
        run.lastErrorCode(),
        run.updatedAt(),
        run.completedAt());
  }

  private ChallengeVisualJobView view(Job job, Run run) {
    return new ChallengeVisualJobView(
        job.jobId(),
        job.runId(),
        job.challengeEventId(),
        job.state(),
        job.attemptNumber(),
        run.maximumAttempts(),
        job.workerId(),
        job.claimEpoch(),
        job.leaseExpiresAt(),
        job.decision() == null ? null : VisualDecision.valueOf(job.decision()),
        readActions(job.actions()),
        job.confidence(),
        job.failureCode(),
        job.updatedAt());
  }

  private void appendAudit(
      String tenantId,
      String sessionId,
      String runId,
      String eventType,
      String result,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            eventType,
            "SYSTEM",
            SYSTEM_ACTOR,
            "CHALLENGE_AUTOMATION_RUN",
            runId,
            eventType,
            result,
            details,
            runId));
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!tenantId.equals(sessions.require(sessionId).tenantId()))
      throw new SessionNotFoundException(sessionId);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Challenge visual actions are not serializable", exception);
    }
  }

  private List<ChallengeVisualAction> readActions(String value) {
    if (value == null) return List.of();
    try {
      return objectMapper.readValue(value, new TypeReference<List<ChallengeVisualAction>>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Challenge visual actions are invalid", exception);
    }
  }

  private static String actionSummary(List<ChallengeVisualAction> actions) {
    return actions.stream()
        .map(action -> action.actionType().name() + "x" + action.repeatCount())
        .reduce((left, right) -> left + "," + right)
        .orElse("NONE");
  }

  private static String token() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static String safeCode(String value, String fallback) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{2,127}$") ? value : fallback;
  }

  private static Instant instant(ResultSet result, String name) throws SQLException {
    var value = result.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private record Run(
      String runId,
      String tenantId,
      String sessionId,
      String taskId,
      String currentChallengeEventId,
      String state,
      int attemptCount,
      int maximumAttempts,
      BigDecimal minimumConfidence,
      boolean allowMultiClick,
      boolean allowSlide,
      int motionMinimumSteps,
      int motionMaximumSteps,
      int motionMinimumDelayMs,
      int motionMaximumDelayMs,
      BigDecimal targetOffsetRatio,
      String lastAction,
      String lastErrorCode,
      Instant updatedAt,
      Instant completedAt) {}

  private record Job(
      String jobId,
      String runId,
      String tenantId,
      String sessionId,
      String challengeEventId,
      int attemptNumber,
      String evidenceId,
      String state,
      String workerId,
      String claimTokenHash,
      long claimEpoch,
      Instant leaseExpiresAt,
      String operationId,
      String decision,
      String actions,
      BigDecimal confidence,
      String failureCode,
      Instant updatedAt) {}

  public static final class ChallengeAutomationRejectedException extends ResponseStatusException {
    public ChallengeAutomationRejectedException(String reason) {
      super(HttpStatus.CONFLICT, reason);
    }
  }
}
