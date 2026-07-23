package io.browsercloud.api;

import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.operation.OwnerType;
import java.time.Instant;
import java.util.Set;

/** 操作视图。 */
public record OperationView(
    String operationId,
    OwnerType ownerType,
    String actorId,
    OperationMode mode,
    int priority,
    long coordinatorTerm,
    long contextEpoch,
    long operationEpoch,
    String workflowId,
    boolean cancellable,
    boolean preemptible,
    OperationPhase phase,
    OperationState state,
    Set<String> allowedCapabilities,
    Instant deadline) {}
