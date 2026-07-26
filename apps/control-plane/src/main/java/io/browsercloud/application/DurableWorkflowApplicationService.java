package io.browsercloud.application;

import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.workflow.WorkflowState;
import io.browsercloud.persistence.DurableWorkflowEntity;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import io.browsercloud.persistence.WorkflowDeadLetterEntity;
import io.browsercloud.persistence.WorkflowDeadLetterJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Workflow Stage A: fencing, idempotency, commit markers, compensation and DLQ. */
@Service
public class DurableWorkflowApplicationService {

  private final DurableWorkflowJpaRepository repository;
  private final WorkflowDeadLetterJpaRepository deadLetterRepository;

  public DurableWorkflowApplicationService(
      DurableWorkflowJpaRepository repository,
      WorkflowDeadLetterJpaRepository deadLetterRepository) {
    this.repository = repository;
    this.deadLetterRepository = deadLetterRepository;
  }

  @Transactional
  public String start(
      String tenantId,
      ExclusiveOperation operation,
      String workflowType,
      String idempotencyKey,
      String compensationAction) {
    var existing = repository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
      var workflow = existing.orElseThrow();
      if (!workflow.getOperationId().equals(operation.operationId())
          || !workflow.getWorkflowType().equals(workflowType)) {
        throw new IllegalStateException("Workflow idempotency key was reused");
      }
      return workflow.getWorkflowId();
    }
    var now = Instant.now();
    var entity = new DurableWorkflowEntity();
    entity.setWorkflowId("wf_" + compactId());
    entity.setTenantId(tenantId);
    entity.setSessionId(operation.sessionId());
    entity.setOperationId(operation.operationId());
    entity.setWorkflowType(workflowType);
    entity.setAttempt(1);
    entity.setPriority(operation.priority());
    entity.setState(WorkflowState.RUNNING.name());
    entity.setPhase(operation.phase().name());
    entity.setWorkerId("browser-node");
    entity.setCoordinatorTerm(operation.coordinatorTerm());
    entity.setContextEpoch(operation.contextEpoch());
    entity.setOperationEpoch(operation.operationEpoch());
    entity.setDispatchedAt(now);
    entity.setStartedAt(now);
    entity.setHeartbeatAt(now);
    entity.setPhaseDeadline(operation.deadline());
    entity.setOperationDeadline(operation.deadline());
    entity.setCancellationEpoch(0);
    entity.setIdempotencyKey(idempotencyKey);
    entity.setCompensationAction(compensationAction);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    return repository.save(entity).getWorkflowId();
  }

  @Transactional
  public CompletionResult completeCallback(
      String tenantId,
      String sessionId,
      long coordinatorTerm,
      long contextEpoch,
      long operationEpoch,
      String receipt) {
    var workflow =
        repository
            .findFirstByTenantIdAndSessionIdAndOperationEpochOrderByAttemptDesc(
                tenantId, sessionId, operationEpoch)
            .orElse(null);
    if (workflow == null
        || workflow.getCoordinatorTerm() != coordinatorTerm
        || workflow.getContextEpoch() != contextEpoch) {
      return CompletionResult.STALE;
    }
    if (WorkflowState.COMPLETED.name().equals(workflow.getState())) {
      return receipt.equals(workflow.getExternalReceipt())
          ? CompletionResult.DUPLICATE
          : CompletionResult.STALE;
    }
    if (!WorkflowState.RUNNING.name().equals(workflow.getState())) {
      return CompletionResult.STALE;
    }
    var now = Instant.now();
    workflow.transition(WorkflowState.COMPLETING, now);
    workflow.setExternalReceipt(receipt);
    workflow.setCommitMarker(
        sha256(
            String.join(
                "\u0000",
                workflow.getWorkflowId(),
                tenantId,
                sessionId,
                Long.toString(coordinatorTerm),
                Long.toString(contextEpoch),
                Long.toString(operationEpoch),
                receipt)));
    workflow.transition(WorkflowState.COMPLETED, now);
    repository.save(workflow);
    return CompletionResult.COMMITTED;
  }

  @Transactional
  public TimeoutDecision timeout(DurableWorkflowEntity workflow, String reason) {
    var current = repository.findById(workflow.getWorkflowId()).orElseThrow();
    if (!WorkflowState.RUNNING.name().equals(current.getState())
        && !WorkflowState.DISPATCHED.name().equals(current.getState())
        && !WorkflowState.COMPLETING.name().equals(current.getState())) {
      return new TimeoutDecision(current, false);
    }
    var now = Instant.now();
    current.transition(WorkflowState.TIMED_OUT, now);
    current.setFailureReason(reason);
    repository.save(current);
    return new TimeoutDecision(current, true);
  }

  @Transactional
  public void markCompensated(DurableWorkflowEntity workflow, String receipt) {
    var current = repository.findById(workflow.getWorkflowId()).orElseThrow();
    var now = Instant.now();
    current.transition(WorkflowState.COMPENSATING, now);
    current.setExternalReceipt(receipt);
    current.transition(WorkflowState.COMPENSATED, now);
    current.setCommitMarker(sha256(current.getWorkflowId() + "\u0000" + receipt));
    repository.save(current);
  }

  @Transactional
  public void deadLetter(DurableWorkflowEntity workflow, String reason) {
    var current = repository.findById(workflow.getWorkflowId()).orElseThrow();
    var now = Instant.now();
    if (WorkflowState.TIMED_OUT.name().equals(current.getState())
        || WorkflowState.FAILED.name().equals(current.getState())
        || WorkflowState.ORPHANED.name().equals(current.getState())
        || WorkflowState.COMPENSATING.name().equals(current.getState())) {
      current.transition(WorkflowState.DEAD_LETTER, now);
    }
    current.setFailureReason(reason);
    repository.save(current);
    deadLetterRepository.save(
        new WorkflowDeadLetterEntity(
            "dlq_" + compactId(),
            current.getWorkflowId(),
            current.getTenantId(),
            current.getSessionId(),
            current.getOperationId(),
            reason,
            "{\"source\":\"deadline-scanner\"}",
            now));
  }

  private static String compactId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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

  public enum CompletionResult {
    COMMITTED,
    DUPLICATE,
    STALE
  }

  public record TimeoutDecision(DurableWorkflowEntity workflow, boolean timedOut) {}
}
