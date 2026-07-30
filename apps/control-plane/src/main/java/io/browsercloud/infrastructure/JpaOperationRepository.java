package io.browsercloud.infrastructure;

import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.exceptions.ActiveOperationExistsException;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.domain.operation.*;
import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Operation Repository JPA 实现。 */
@Repository
public class JpaOperationRepository implements OperationRepository {

  private final ExclusiveOperationJpaRepository operationJpa;

  public JpaOperationRepository(ExclusiveOperationJpaRepository operationJpa) {
    this.operationJpa = operationJpa;
  }

  @Override
  public void ensureNoActiveOperation(String sessionId) {
    var activeOp = operationJpa.findBySessionIdAndState(sessionId, "ACTIVE");
    if (activeOp.isPresent()) {
      throw new ActiveOperationExistsException(sessionId, activeOp.get().getOperationId());
    }
  }

  @Override
  public Optional<ExclusiveOperation> findActive(String sessionId) {
    return operationJpa.findBySessionIdAndState(sessionId, "ACTIVE").map(this::toDomain);
  }

  @Override
  public Map<String, ExclusiveOperation> findActiveBySessionIds(Collection<String> sessionIds) {
    if (sessionIds.isEmpty()) {
      return Map.of();
    }
    return operationJpa.findAllBySessionIdInAndState(sessionIds, "ACTIVE").stream()
        .map(this::toDomain)
        .collect(
            Collectors.toUnmodifiableMap(
                ExclusiveOperation::sessionId,
                Function.identity(),
                (first, duplicate) -> {
                  throw new IllegalStateException(
                      "Multiple active Operations for Session " + first.sessionId());
                }));
  }

  @Override
  public long nextOperationEpoch(String sessionId) {
    return operationJpa.nextOperationEpoch(sessionId);
  }

  @Override
  public long countSince(String sessionId, OperationMode mode, Instant since) {
    return operationJpa.countBySessionIdAndModeAndCreatedAtAfter(sessionId, mode.name(), since);
  }

  @Override
  @Transactional
  public void insert(ExclusiveOperation operation) {
    var entity = new ExclusiveOperationEntity();
    entity.setOperationId(operation.operationId());
    entity.setSessionId(operation.sessionId());
    entity.setOwnerType(operation.ownerType().name());
    entity.setActorId(operation.actorId());
    entity.setMode(operation.mode().name());
    entity.setPriority(operation.priority());
    entity.setOperationEpoch(operation.operationEpoch());
    entity.setCoordinatorTerm(operation.coordinatorTerm());
    entity.setContextEpoch(operation.contextEpoch());
    entity.setWorkflowId(operation.workflowId());
    entity.setCancellable(operation.cancellable());
    entity.setPreemptible(operation.preemptible());
    entity.setPhase(operation.phase().name());
    entity.setState(operation.state().name());
    entity.setDeadline(operation.deadline());
    entity.setAllowedCapabilities(
        operation.allowedCapabilities().stream()
            .sorted()
            .map(capability -> "\"" + capability + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]")));
    entity.setCreatedAt(operation.createdAt());
    entity.setCompletedAt(operation.completedAt());
    operationJpa.save(entity);
  }

  @Override
  @Transactional
  public void attachWorkflow(String operationId, String workflowId) {
    var entity =
        operationJpa
            .findById(operationId)
            .orElseThrow(() -> new StaleOperationException(operationId, "EXISTS", "NOT_FOUND"));
    if (entity.getWorkflowId() != null && !entity.getWorkflowId().equals(workflowId)) {
      throw new StaleOperationException(operationId, workflowId, entity.getWorkflowId());
    }
    entity.setWorkflowId(workflowId);
    operationJpa.save(entity);
  }

  @Override
  @Transactional
  public void transition(String operationId, OperationState expected, OperationState target) {
    var entity =
        operationJpa
            .findById(operationId)
            .orElseThrow(
                () -> new StaleOperationException(operationId, expected.name(), "NOT_FOUND"));

    if (!entity.getState().equals(expected.name())) {
      throw new StaleOperationException(operationId, expected.name(), entity.getState());
    }

    entity.setState(target.name());
    if (target == OperationState.COMMITTED
        || target == OperationState.ABORTED
        || target == OperationState.TIMED_OUT) {
      entity.setCompletedAt(Instant.now());
    }
    operationJpa.save(entity);
  }

  @Override
  @Transactional
  public void transitionPhase(String operationId, OperationPhase expected, OperationPhase target) {
    var entity =
        operationJpa
            .findById(operationId)
            .orElseThrow(
                () -> new StaleOperationException(operationId, expected.name(), "NOT_FOUND"));
    if (!entity.getPhase().equals(expected.name())) {
      throw new StaleOperationException(operationId, expected.name(), entity.getPhase());
    }
    entity.setPhase(target.name());
    operationJpa.save(entity);
  }

  private ExclusiveOperation toDomain(ExclusiveOperationEntity entity) {
    return new ExclusiveOperation(
        entity.getOperationId(),
        entity.getSessionId(),
        OwnerType.valueOf(entity.getOwnerType()),
        entity.getActorId(),
        OperationMode.valueOf(entity.getMode()),
        entity.getPriority(),
        entity.getCoordinatorTerm(),
        entity.getContextEpoch(),
        entity.getOperationEpoch(),
        entity.getWorkflowId(),
        entity.isCancellable(),
        entity.isPreemptible(),
        OperationPhase.valueOf(entity.getPhase()),
        OperationState.valueOf(entity.getState()),
        Set.of(),
        entity.getDeadline(),
        entity.getCreatedAt(),
        entity.getCompletedAt());
  }
}
