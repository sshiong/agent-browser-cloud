package io.browsercloud.application;

import static io.browsercloud.api.AgentOutcomeVerifierModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.domain.agent.AgentModels.RiskClass;
import io.browsercloud.domain.agent.AgentModels.ToolExecutionResult;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Independent post-execution semantic verifier.
 *
 * <p>An action ACK can only move a task to {@code VERIFYING_OUTCOME}. Completion requires an
 * independently leased model verdict over the exact, authoritative final Browser State. The queue
 * stores hashes and accounting only; the bounded payload is reconstructed from current authorities
 * and contains no capabilities, raw page body, screenshots, values or element IDs.
 */
@Service
public class AgentOutcomeVerifierApplicationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> REASON_CODES =
      Set.of(
          "GOAL_SATISFIED",
          "GOAL_NOT_SATISFIED",
          "BUSINESS_ERROR_VISIBLE",
          "EXPECTED_STATE_MISSING",
          "WRONG_TARGET_OUTCOME",
          "STATE_STALE",
          "STATE_INCOMPLETE",
          "INSUFFICIENT_EVIDENCE",
          "MODEL_UNCERTAIN");

  private final JdbcTemplate jdbc;
  private final AgentTaskJpaRepository tasks;
  private final BrowserStateRepository browserStates;
  private final AuditApplicationService audit;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final Duration claimLease;
  private final int maximumAttempts;
  private final BigDecimal minimumConfidence;
  private final ModelDeployment deployment;

  public AgentOutcomeVerifierApplicationService(
      JdbcTemplate jdbc,
      AgentTaskJpaRepository tasks,
      BrowserStateRepository browserStates,
      AuditApplicationService audit,
      ObjectMapper objectMapper,
      @Value("${agent.outcome-verifier.external.enabled:false}") boolean enabled,
      @Value("${agent.outcome-verifier.external.claim-lease-seconds:90}") long leaseSeconds,
      @Value("${agent.outcome-verifier.external.maximum-attempts:3}") int maximumAttempts,
      @Value("${agent.outcome-verifier.minimum-confidence:0.80}") BigDecimal minimumConfidence,
      @Value("${agent.outcome-verifier.deployment-id:outcome-local-v1}") String deploymentId,
      @Value("${agent.outcome-verifier.provider-type:OPENAI_RESPONSES}") String providerType,
      @Value("${agent.outcome-verifier.model-name:outcome-local}") String modelName,
      @Value("${agent.outcome-verifier.model-revision:local-v1}") String modelRevision,
      @Value("${agent.outcome-verifier.data-policy:REDACTED_FINAL_STATE}") String dataPolicy,
      @Value("${agent.outcome-verifier.maximum-output-tokens:512}") int maximumOutputTokens,
      @Value("${agent.outcome-verifier.input-price-micros-per-million-tokens:0}") long inputPrice,
      @Value("${agent.outcome-verifier.output-price-micros-per-million-tokens:0}")
          long outputPrice) {
    if (leaseSeconds < 30 || leaseSeconds > 300) {
      throw new IllegalArgumentException("Outcome Verifier claim lease must be 30..300 seconds");
    }
    if (maximumAttempts < 1 || maximumAttempts > 10) {
      throw new IllegalArgumentException("Outcome Verifier maximum attempts must be 1..10");
    }
    if (minimumConfidence.compareTo(BigDecimal.ZERO) < 0
        || minimumConfidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("Outcome Verifier minimum confidence must be in [0,1]");
    }
    if (!"OPENAI_RESPONSES".equals(providerType)) {
      throw new IllegalArgumentException("unsupported Outcome Verifier provider type");
    }
    if (maximumOutputTokens < 64 || maximumOutputTokens > 4096) {
      throw new IllegalArgumentException("Outcome Verifier maximum output tokens must be 64..4096");
    }
    if (inputPrice < 0 || outputPrice < 0) {
      throw new IllegalArgumentException("Outcome Verifier model prices cannot be negative");
    }
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.browserStates = browserStates;
    this.audit = audit;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.claimLease = Duration.ofSeconds(leaseSeconds);
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
            inputPrice,
            outputPrice);
  }

  public boolean enabled() {
    return enabled;
  }

  /** Queues verification for the exact final state and moves the task out of RUNNING. */
  @Transactional
  public void enqueue(AgentTaskEntity task, List<ToolExecutionResult> results, Instant now) {
    if (!enabled) return;
    if (!"RUNNING".equals(task.getState())) {
      throw new AgentOutcomeRejectedException("AGENT_TASK_NOT_RUNNING");
    }
    var snapshot = requireSnapshot(task);
    var provisional = payload(task, results, snapshot, null);
    var evidenceHash = sha256(write(provisional));
    var payload = payload(task, results, snapshot, evidenceHash);
    var inputHash = sha256(write(payload));
    if (findByTaskId(task.getTaskId()).isPresent()) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_ALREADY_EXISTS");
    }
    var jobId = id("ojob_");
    var verificationId = id("out_");
    jdbc.update(
        """
        INSERT INTO agent_outcome_verification_jobs(
          job_id, verification_id, task_id, tenant_id, session_id, protocol_version,
          evidence_hash, state_version, target_revision, state_hash, state, attempt,
          maximum_attempts, claim_epoch, available_at, deployment_id, provider_type,
          model_name, model_revision, data_policy, maximum_output_tokens,
          input_price_micros_per_mtok, output_price_micros_per_mtok, input_hash,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'outcome-verifier-worker/v1', ?, ?, ?, ?, 'QUEUED', 0,
                  ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        jobId,
        verificationId,
        task.getTaskId(),
        task.getTenantId(),
        task.getSessionId(),
        evidenceHash,
        snapshot.state().stateVersion(),
        snapshot.state().targetRevision(),
        snapshot.state().stateHash(),
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
    appendEvent(jobId, "ENQUEUED", "QUEUED", 0, null, 0, null, null, now);
    task.awaitOutcomeVerification(
        task.getCurrentStep(), write(results), verificationId, evidenceHash, now);
    appendAudit(
        task,
        verificationId,
        "AGENT_OUTCOME_VERIFICATION_QUEUED",
        "QUEUED",
        Map.of(
            "evidenceHash", evidenceHash,
            "stateVersion", snapshot.state().stateVersion(),
            "stateHash", snapshot.state().stateHash()));
  }

  @Transactional
  public Optional<AgentOutcomeJobClaimView> claim(
      ClaimAgentOutcomeJobRequest request, String workerId) {
    if (!enabled) throw new AgentOutcomeRejectedException("AGENT_OUTCOME_VERIFIER_NOT_ENABLED");
    if (!Boolean.TRUE.equals(request.capabilities().get("openai-responses-v1"))) {
      throw new AgentOutcomeRejectedException("OUTCOME_VERIFIER_WORKER_CAPABILITY_MISSING");
    }
    requireDeployment(request.deploymentId(), request.modelRevision());
    var now = Instant.now();
    reapExpired(now, 50);
    var ids =
        jdbc.queryForList(
            """
            SELECT job_id FROM agent_outcome_verification_jobs
             WHERE state = 'QUEUED' AND available_at <= ? AND attempt < maximum_attempts
               AND deployment_id = ? AND model_revision = ?
             ORDER BY available_at, created_at, job_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            request.deploymentId(),
            request.modelRevision());
    if (ids.isEmpty()) return Optional.empty();
    var job = requireJob(ids.getFirst());
    var task = requireTask(job.taskId(), job.tenantId());
    var ready = readyPayload(job, task, now);
    if (ready.isEmpty()) return Optional.empty();
    var payload = ready.orElseThrow();
    job = requireJob(job.jobId());
    var token = token();
    var leaseUntil = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE agent_outcome_verification_jobs
               SET state = 'CLAIMED', attempt = attempt + 1, worker_id = ?,
                   claim_epoch = claim_epoch + 1, claim_token_hash = ?, lease_expires_at = ?,
                   failure_code = NULL, updated_at = ?
             WHERE job_id = ? AND state = 'QUEUED' AND attempt < maximum_attempts
            """,
            workerId,
            sha256(token),
            sqlTime(leaseUntil),
            sqlTime(now),
            job.jobId());
    if (changed != 1) throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_CLAIM_FENCED");
    var claimed = requireJob(job.jobId());
    task.markOutcomeVerifierRunning(now);
    tasks.save(task);
    appendEvent(
        job.jobId(),
        "CLAIMED",
        "CLAIMED",
        claimed.attempt(),
        workerId,
        claimed.claimEpoch(),
        null,
        null,
        now);
    return Optional.of(
        new AgentOutcomeJobClaimView(
            token, toView(claimed), payload, leaseUntil, claimed.claimEpoch()));
  }

  @Transactional
  public AgentOutcomeJobView start(
      String jobId, AgentOutcomeJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = activeClaim(jobId, request.claimToken(), workerId, now, "CLAIMED");
    var leaseUntil = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE agent_outcome_verification_jobs
               SET state = 'EXECUTING', started_at = COALESCE(started_at, ?),
                   lease_expires_at = ?, updated_at = ?
             WHERE job_id = ? AND state = 'CLAIMED' AND worker_id = ? AND claim_epoch = ?
               AND claim_token_hash = ?
            """,
            sqlTime(now),
            sqlTime(leaseUntil),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_START_FENCED");
    appendEvent(
        jobId, "STARTED", "EXECUTING", job.attempt(), workerId, job.claimEpoch(), null, null, now);
    return toView(requireJob(jobId));
  }

  @Transactional
  public AgentOutcomeJobView heartbeat(
      String jobId, AgentOutcomeJobClaimRequest request, String workerId) {
    var now = Instant.now();
    var job = activeClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    var leaseUntil = now.plus(claimLease);
    var changed =
        jdbc.update(
            """
            UPDATE agent_outcome_verification_jobs SET lease_expires_at = ?, updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ? AND claim_epoch = ?
               AND claim_token_hash = ?
            """,
            sqlTime(leaseUntil),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_HEARTBEAT_FENCED");
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
  public AgentOutcomeJobView complete(
      String jobId, CompleteAgentOutcomeJobRequest request, String workerId) {
    var now = Instant.now();
    var job = activeClaim(jobId, request.claimToken(), workerId, now, "EXECUTING");
    requireDeployment(request.deploymentId(), request.modelRevision());
    if (request.outputTokens() > job.maximumOutputTokens()) {
      throw new AgentOutcomeRejectedException("OUTCOME_MODEL_OUTPUT_BUDGET_EXCEEDED");
    }
    var task = requireTask(job.taskId(), job.tenantId());
    requireExactPayload(job, task);
    var reasons = normalizedReasons(request.reasonCodes());
    var outcome = policyDecision(request.decision(), reasons, request.confidence());
    var cost =
        tokenCost(request.inputTokens(), job.inputPriceMicros())
            + tokenCost(request.outputTokens(), job.outputPriceMicros());
    var finalState = outcome.decision().name();
    var changed =
        jdbc.update(
            """
            UPDATE agent_outcome_verification_jobs
               SET state = ?, decision = ?, reason_codes = ?::jsonb, confidence = ?,
                   output_hash = ?, provider_request_id = ?, input_tokens = ?, output_tokens = ?,
                   cost_micros = ?, latency_ms = ?, completed_at = ?, failure_code = NULL,
                   worker_id = NULL, claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
             WHERE job_id = ? AND state = 'EXECUTING' AND worker_id = ? AND claim_epoch = ?
               AND claim_token_hash = ?
            """,
            finalState,
            finalState,
            write(outcome.reasons()),
            request.confidence(),
            request.outputHash(),
            request.providerRequestId(),
            request.inputTokens(),
            request.outputTokens(),
            cost,
            request.latencyMs(),
            sqlTime(now),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_COMPLETE_FENCED");
    }
    task.recordOutcomeAccounting(
        job.deploymentId(),
        job.modelName(),
        job.modelRevision(),
        request.inputTokens(),
        request.outputTokens(),
        cost,
        request.latencyMs());
    if (outcome.decision() == OutcomeDecision.VERIFIED) {
      task.verifyOutcome(write(outcome.reasons()), now);
    } else {
      task.rejectOutcome(write(outcome.reasons()), now);
    }
    tasks.save(task);
    appendEvent(
        jobId,
        finalState,
        finalState,
        job.attempt(),
        workerId,
        job.claimEpoch(),
        finalState,
        null,
        now);
    appendAudit(
        task,
        job.verificationId(),
        "AGENT_OUTCOME_VERIFICATION_COMPLETED",
        finalState,
        Map.of(
            "evidenceHash", job.evidenceHash(),
            "reasonCodes", outcome.reasons(),
            "confidence", request.confidence(),
            "deploymentId", job.deploymentId(),
            "modelRevision", job.modelRevision(),
            "inputTokens", request.inputTokens(),
            "outputTokens", request.outputTokens(),
            "costMicros", cost,
            "latencyMs", request.latencyMs()));
    return toView(requireJob(jobId));
  }

  @Transactional
  public AgentOutcomeJobView fail(
      String jobId, FailAgentOutcomeJobRequest request, String workerId) {
    var now = Instant.now();
    var job = activeClaim(jobId, request.claimToken(), workerId, now, null);
    var retry = request.retryable() && job.attempt() < job.maximumAttempts();
    var state = retry ? "QUEUED" : "FAILED";
    var changed =
        jdbc.update(
            """
            UPDATE agent_outcome_verification_jobs
               SET state = ?, available_at = ?, completed_at = ?, failure_code = ?,
                   worker_id = NULL, claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
             WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING') AND worker_id = ?
               AND claim_epoch = ? AND claim_token_hash = ?
            """,
            state,
            sqlTime(retry ? now.plusSeconds(backoffSeconds(job.attempt())) : now),
            retry ? null : sqlTime(now),
            request.failureCode(),
            sqlTime(now),
            jobId,
            workerId,
            job.claimEpoch(),
            sha256(request.claimToken()));
    if (changed != 1) throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_FAIL_FENCED");
    var task = requireTask(job.taskId(), job.tenantId());
    if (retry) task.requeueOutcomeVerifier(now);
    else task.failOutcomeVerifier(request.failureCode(), now);
    tasks.save(task);
    appendEvent(
        jobId,
        retry ? "REQUEUED" : "FAILED",
        state,
        job.attempt(),
        workerId,
        job.claimEpoch(),
        null,
        request.failureCode(),
        now);
    return toView(requireJob(jobId));
  }

  @Scheduled(fixedDelayString = "${agent.outcome-verifier.external.reaper-interval-ms:15000}")
  @Transactional
  public void reapExpiredClaims() {
    if (enabled) reapExpired(Instant.now(), 100);
  }

  void reapExpired(Instant now, int limit) {
    var ids =
        jdbc.queryForList(
            """
            SELECT job_id FROM agent_outcome_verification_jobs
             WHERE state IN ('CLAIMED', 'EXECUTING') AND lease_expires_at <= ?
             ORDER BY lease_expires_at, job_id LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            String.class,
            sqlTime(now),
            Math.max(1, Math.min(limit, 500)));
    for (var id : ids) {
      var job = requireJob(id);
      var retry = job.attempt() < job.maximumAttempts();
      var state = retry ? "QUEUED" : "FAILED";
      jdbc.update(
          """
          UPDATE agent_outcome_verification_jobs
             SET state = ?, available_at = ?, completed_at = ?,
                 failure_code = 'OUTCOME_VERIFIER_LEASE_EXPIRED', worker_id = NULL,
                 claim_token_hash = NULL, lease_expires_at = NULL, updated_at = ?
           WHERE job_id = ? AND state IN ('CLAIMED', 'EXECUTING') AND claim_epoch = ?
          """,
          state,
          sqlTime(retry ? now.plusSeconds(backoffSeconds(job.attempt())) : now),
          retry ? null : sqlTime(now),
          sqlTime(now),
          id,
          job.claimEpoch());
      var task = requireTask(job.taskId(), job.tenantId());
      if (retry) task.requeueOutcomeVerifier(now);
      else task.failOutcomeVerifier("OUTCOME_VERIFIER_LEASE_EXPIRED", now);
      tasks.save(task);
      appendEvent(
          id,
          retry ? "REQUEUED" : "FAILED",
          state,
          job.attempt(),
          job.workerId(),
          job.claimEpoch(),
          null,
          "OUTCOME_VERIFIER_LEASE_EXPIRED",
          now);
    }
  }

  private Optional<AgentOutcomePayload> readyPayload(
      OutcomeJob job, AgentTaskEntity task, Instant now) {
    if (!"VERIFYING_OUTCOME".equals(task.getState())
        || !job.verificationId().equals(task.getOutcomeVerificationId())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_TASK_CHANGED");
    }
    var snapshot =
        browserStates
            .find(task.getSessionId())
            .filter(value -> value.tenantId().equals(task.getTenantId()))
            .orElse(null);
    if (snapshot == null) return deferQueued(job, now);
    var state = snapshot.state();
    var freshness = BrowserStateFreshness.describe(state, snapshot.observedAt(), now);
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())
        || "STALE".equals(freshness.freshness())
        || !"STABLE".equals(freshness.pageActivity())) {
      return deferQueued(job, now);
    }
    var results =
        read(task.getExecutionResults(), new TypeReference<List<ToolExecutionResult>>() {});
    var provisional = payload(task, results, snapshot, null);
    var evidenceHash = sha256(write(provisional));
    var payload = payload(task, results, snapshot, evidenceHash);
    var inputHash = sha256(write(payload));
    if (state.stateVersion() != job.stateVersion()
        || state.targetRevision() != job.targetRevision()
        || !constantEquals(state.stateHash(), job.stateHash())
        || !constantEquals(evidenceHash, job.evidenceHash())
        || !constantEquals(inputHash, job.inputHash())) {
      var changed =
          jdbc.update(
              """
              UPDATE agent_outcome_verification_jobs
                 SET evidence_hash = ?, state_version = ?, target_revision = ?, state_hash = ?,
                     input_hash = ?, available_at = ?, updated_at = ?
               WHERE job_id = ? AND state = 'QUEUED' AND claim_epoch = ?
              """,
              evidenceHash,
              state.stateVersion(),
              state.targetRevision(),
              state.stateHash(),
              inputHash,
              sqlTime(now),
              sqlTime(now),
              job.jobId(),
              job.claimEpoch());
      if (changed != 1) {
        throw new AgentOutcomeRejectedException("AGENT_OUTCOME_EVIDENCE_REBIND_FENCED");
      }
      task.rebindOutcomeEvidence(evidenceHash, now);
      tasks.save(task);
    }
    return Optional.of(payload);
  }

  private Optional<AgentOutcomePayload> deferQueued(OutcomeJob job, Instant now) {
    jdbc.update(
        """
        UPDATE agent_outcome_verification_jobs SET available_at = ?, updated_at = ?
         WHERE job_id = ? AND state = 'QUEUED' AND claim_epoch = ?
        """,
        sqlTime(now.plusSeconds(2)),
        sqlTime(now),
        job.jobId(),
        job.claimEpoch());
    return Optional.empty();
  }

  private AgentOutcomePayload requireExactPayload(OutcomeJob job, AgentTaskEntity task) {
    if (!"VERIFYING_OUTCOME".equals(task.getState())
        || !job.verificationId().equals(task.getOutcomeVerificationId())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_TASK_CHANGED");
    }
    var snapshot = requireSnapshot(task);
    if (snapshot.state().stateVersion() != job.stateVersion()
        || snapshot.state().targetRevision() != job.targetRevision()
        || !MessageDigest.isEqual(
            snapshot.state().stateHash().getBytes(StandardCharsets.US_ASCII),
            job.stateHash().getBytes(StandardCharsets.US_ASCII))) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_EVIDENCE_CHANGED");
    }
    var results =
        read(task.getExecutionResults(), new TypeReference<List<ToolExecutionResult>>() {});
    var provisional = payload(task, results, snapshot, null);
    var evidenceHash = sha256(write(provisional));
    var payload = payload(task, results, snapshot, evidenceHash);
    if (!constantEquals(evidenceHash, job.evidenceHash())
        || !constantEquals(sha256(write(payload)), job.inputHash())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_INPUT_CHANGED");
    }
    return payload;
  }

  private AgentOutcomePayload payload(
      AgentTaskEntity task,
      List<ToolExecutionResult> results,
      BrowserStateRepository.Snapshot snapshot,
      String evidenceHash) {
    var state = snapshot.state();
    var execution =
        java.util.stream.IntStream.range(0, results.size())
            .mapToObj(
                index -> {
                  var result = results.get(index);
                  return new OutcomeExecutionEvidence(
                      index,
                      result.stepId(),
                      result.toolId(),
                      result.status(),
                      result.resultHash(),
                      AgentDataMinimizer.redact(result.verification()));
                })
            .toList();
    var targets =
        state.targets().stream()
            .filter(target -> target.visible() && target.enabled())
            .limit(40)
            .map(
                target ->
                    new OutcomeTargetEvidence(
                        normalizedText(target.role()),
                        target.name() == null ? "" : AgentDataMinimizer.redact(target.name()),
                        normalizedText(target.controlType()),
                        target.visible(),
                        target.enabled(),
                        target.checked(),
                        target.selected()))
            .toList();
    var finalState =
        new OutcomeStateEvidence(
            state.stateVersion(),
            state.targetRevision(),
            state.stateHash(),
            safeUrl(state.url()),
            AgentDataMinimizer.redact(state.title()),
            state.stateQuality(),
            normalizedText(state.documentReadyState()),
            state.networkQuietMillis(),
            state.networkEvidenceFresh(),
            snapshot.observedAt(),
            targets);
    return new AgentOutcomePayload(
        task.getTaskId(),
        AgentDataMinimizer.redact(task.getGoal()),
        RiskClass.valueOf(task.getRiskClass()),
        read(task.getAllowedDomains(), new TypeReference<List<String>>() {}),
        execution,
        finalState,
        evidenceHash,
        deployment.dataPolicy());
  }

  private OutcomePolicy policyDecision(
      OutcomeDecision requested, List<String> reasons, BigDecimal confidence) {
    if (confidence.compareTo(minimumConfidence) < 0) {
      return new OutcomePolicy(OutcomeDecision.NOT_VERIFIED, List.of("MODEL_UNCERTAIN"));
    }
    if (requested == OutcomeDecision.VERIFIED && !reasons.equals(List.of("GOAL_SATISFIED"))) {
      throw new AgentOutcomeRejectedException("OUTCOME_VERIFIED_REASON_INVALID");
    }
    if (requested == OutcomeDecision.NOT_VERIFIED && reasons.contains("GOAL_SATISFIED")) {
      throw new AgentOutcomeRejectedException("OUTCOME_NOT_VERIFIED_REASON_INVALID");
    }
    return new OutcomePolicy(requested, reasons);
  }

  private List<String> normalizedReasons(List<String> values) {
    var reasons = new LinkedHashSet<String>();
    for (var value : values) {
      var normalized = value.trim().toUpperCase(Locale.ROOT);
      if (!REASON_CODES.contains(normalized)) {
        throw new AgentOutcomeRejectedException("OUTCOME_REASON_CODE_UNSUPPORTED");
      }
      reasons.add(normalized);
    }
    if (reasons.isEmpty()) throw new AgentOutcomeRejectedException("OUTCOME_REASON_CODE_REQUIRED");
    return List.copyOf(reasons);
  }

  private BrowserStateRepository.Snapshot requireSnapshot(AgentTaskEntity task) {
    var snapshot =
        browserStates
            .find(task.getSessionId())
            .filter(value -> value.tenantId().equals(task.getTenantId()))
            .orElseThrow(() -> new AgentOutcomeRejectedException("AGENT_OUTCOME_STATE_MISSING"));
    var state = snapshot.state();
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_STATE_INCOMPLETE");
    }
    if (snapshot.observedAt() == null || snapshot.observedAt().equals(Instant.EPOCH)) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_STATE_STALE");
    }
    return snapshot;
  }

  private AgentTaskEntity requireTask(String taskId, String tenantId) {
    return tasks
        .findForUpdate(taskId, tenantId)
        .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
  }

  private void requireDeployment(String deploymentId, String modelRevision) {
    if (!deployment.deploymentId().equals(deploymentId)
        || !deployment.modelRevision().equals(modelRevision)) {
      throw new AgentOutcomeRejectedException("OUTCOME_MODEL_DEPLOYMENT_MISMATCH");
    }
  }

  private OutcomeJob activeClaim(
      String jobId, String token, String workerId, Instant now, String requiredState) {
    var job = requireJob(jobId);
    if (requiredState != null && !requiredState.equals(job.state())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_STATE_INVALID");
    }
    if (requiredState == null && !Set.of("CLAIMED", "EXECUTING").contains(job.state())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_STATE_INVALID");
    }
    if (!workerId.equals(job.workerId()) || !constantEquals(sha256(token), job.claimTokenHash())) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_CLAIM_TOKEN_INVALID");
    }
    if (job.leaseExpiresAt() == null || !job.leaseExpiresAt().isAfter(now)) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_LEASE_EXPIRED");
    }
    return job;
  }

  private Optional<OutcomeJob> findByTaskId(String taskId) {
    return jdbc
        .query(
            "SELECT * FROM agent_outcome_verification_jobs WHERE task_id = ?", this::mapJob, taskId)
        .stream()
        .findFirst();
  }

  private OutcomeJob requireJob(String jobId) {
    return jdbc
        .query(
            "SELECT * FROM agent_outcome_verification_jobs WHERE job_id = ?", this::mapJob, jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AgentOutcomeRejectedException("AGENT_OUTCOME_JOB_NOT_FOUND"));
  }

  private OutcomeJob mapJob(ResultSet result, int row) throws SQLException {
    return new OutcomeJob(
        result.getString("job_id"),
        result.getString("verification_id"),
        result.getString("task_id"),
        result.getString("tenant_id"),
        result.getString("session_id"),
        result.getString("protocol_version"),
        result.getString("evidence_hash"),
        result.getLong("state_version"),
        result.getLong("target_revision"),
        result.getString("state_hash"),
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
        result.getString("input_hash"),
        result.getString("decision"),
        read(result.getString("reason_codes"), new TypeReference<List<String>>() {}),
        result.getBigDecimal("confidence"),
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

  private AgentOutcomeJobView toView(OutcomeJob job) {
    return new AgentOutcomeJobView(
        job.jobId(),
        job.verificationId(),
        job.taskId(),
        job.protocolVersion(),
        job.state(),
        job.attempt(),
        job.maximumAttempts(),
        job.workerId(),
        job.claimEpoch(),
        job.leaseExpiresAt(),
        job.availableAt(),
        new OutcomeModelDeploymentView(
            job.deploymentId(),
            job.providerType(),
            job.modelName(),
            job.modelRevision(),
            job.dataPolicy(),
            job.maximumOutputTokens()),
        job.decision() == null ? null : OutcomeDecision.valueOf(job.decision()),
        job.reasonCodes(),
        job.confidence(),
        job.evidenceHash(),
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
        INSERT INTO agent_outcome_verification_events(
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
      String verificationId,
      String action,
      String outcome,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            task.getTenantId(),
            task.getSessionId(),
            action,
            "SYSTEM",
            "agent-outcome-verifier-governance",
            "AGENT_OUTCOME_VERIFICATION",
            verificationId,
            action,
            outcome,
            details,
            UUID.randomUUID().toString()));
  }

  private static String safeUrl(String value) {
    try {
      var uri = URI.create(value);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toASCIIString();
    } catch (Exception exception) {
      throw new AgentOutcomeRejectedException("AGENT_OUTCOME_STATE_URL_INVALID");
    }
  }

  private static String normalizedText(String value) {
    return value == null ? "" : AgentDataMinimizer.redact(value);
  }

  private static String safeIdentifier(String value, int maximumLength) {
    var normalized = value == null ? "" : value.trim();
    if (normalized.isBlank()
        || normalized.length() > maximumLength
        || !normalized.matches("^[A-Za-z0-9._:/-]+$")) {
      throw new IllegalArgumentException("Outcome Verifier model identifier is invalid");
    }
    return normalized;
  }

  private static long tokenCost(int tokens, long price) {
    return BigDecimal.valueOf(tokens)
        .multiply(BigDecimal.valueOf(price))
        .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING)
        .longValueExact();
  }

  private static long backoffSeconds(int attempt) {
    return Math.min(300, 5L << Math.min(6, Math.max(0, attempt - 1)));
  }

  private static boolean constantEquals(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
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
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize Outcome Verifier evidence", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Outcome Verifier evidence", exception);
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

  private record OutcomePolicy(OutcomeDecision decision, List<String> reasons) {}

  private record OutcomeJob(
      String jobId,
      String verificationId,
      String taskId,
      String tenantId,
      String sessionId,
      String protocolVersion,
      String evidenceHash,
      long stateVersion,
      long targetRevision,
      String stateHash,
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
      String inputHash,
      String decision,
      List<String> reasonCodes,
      BigDecimal confidence,
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

  public static class AgentOutcomeRejectedException extends RuntimeException {
    public AgentOutcomeRejectedException(String reason) {
      super(reason);
    }
  }
}
