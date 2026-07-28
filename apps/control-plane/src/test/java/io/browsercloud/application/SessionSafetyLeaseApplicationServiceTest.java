package io.browsercloud.application;

import static io.browsercloud.api.SafePointModels.ApplicationSafetySignalType.CRITICAL_TRANSACTION;
import static io.browsercloud.api.SafePointModels.ApplicationSafetySignalType.PAYMENT_OR_SECURITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.api.SafePointModels.CreateSafetyLeaseRequest;
import io.browsercloud.api.SafePointModels.RenewSafetyLeaseRequest;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.ExclusiveOperationEntity;
import io.browsercloud.persistence.SessionSafetyLeaseEntity;
import io.browsercloud.persistence.SessionSafetyLeaseEventEntity;
import io.browsercloud.persistence.SessionSafetyLeaseEventJpaRepository;
import io.browsercloud.persistence.SessionSafetyLeaseJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class SessionSafetyLeaseApplicationServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";
  private static final String ACTOR_ID = "app-adapter";

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final SessionSafetyLeaseJpaRepository leases =
      mock(SessionSafetyLeaseJpaRepository.class);
  private final SessionSafetyLeaseEventJpaRepository events =
      mock(SessionSafetyLeaseEventJpaRepository.class);
  private final ExclusiveOperationJpaRepository operations =
      mock(ExclusiveOperationJpaRepository.class);
  private final IdempotencyService idempotency = mock(IdempotencyService.class);
  private final SessionSafetyLeaseApplicationService service =
      new SessionSafetyLeaseApplicationService(sessions, leases, events, operations, idempotency);

  @BeforeEach
  void setUp() {
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session(SessionState.RUNNING));
    when(sessions.require(SESSION_ID)).thenReturn(session(SessionState.RUNNING));
    when(operations.findBySessionIdAndState(SESSION_ID, "ACTIVE")).thenReturn(Optional.empty());
    when(leases.save(any(SessionSafetyLeaseEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(events.save(any(SessionSafetyLeaseEventEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(idempotency.claimSafetyLease(
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(5));
    when(idempotency.claimSafetyLeaseMutation(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(6));
  }

  @Test
  void acquirePersistsOwnerBoundCurrentContextLeaseAndAuditEvent() {
    when(leases.findById(anyString())).thenReturn(Optional.empty());

    var result =
        service.acquire(
            SESSION_ID,
            TENANT_ID,
            ACTOR_ID,
            "idem-acquire",
            new CreateSafetyLeaseRequest(PAYMENT_OR_SECURITY, "CHECKOUT_COMMIT", 30));

    assertThat(result.sessionId()).isEqualTo(SESSION_ID);
    assertThat(result.contextEpoch()).isEqualTo(7);
    assertThat(result.ownerActorId()).isEqualTo(ACTOR_ID);
    assertThat(result.state()).isEqualTo("ACTIVE");
    var event = ArgumentCaptor.forClass(SessionSafetyLeaseEventEntity.class);
    verify(events).save(event.capture());
    assertThat(event.getValue().getEventType()).isEqualTo("ACQUIRED");
    assertThat(event.getValue().getLeaseId()).isEqualTo(result.leaseId());
    var order = inOrder(sessions, idempotency, leases, events);
    order.verify(sessions).requireForUpdate(SESSION_ID);
    order
        .verify(idempotency)
        .claimSafetyLease(anyString(), anyString(), anyString(), anyString(), any(), anyString());
    order.verify(leases).findById(result.leaseId());
    order.verify(leases).save(any(SessionSafetyLeaseEntity.class));
    order.verify(events).save(any(SessionSafetyLeaseEventEntity.class));
  }

  @Test
  void idempotentAcquireReplayReturnsExistingLeaseWithoutDuplicateEvent() {
    var existing = activeLease(Instant.now(), 30);
    when(idempotency.claimSafetyLease(
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(existing.getLeaseId());
    when(leases.findById(existing.getLeaseId())).thenReturn(Optional.of(existing));

    var result =
        service.acquire(
            SESSION_ID,
            TENANT_ID,
            ACTOR_ID,
            "idem-replay",
            new CreateSafetyLeaseRequest(CRITICAL_TRANSACTION, "LEDGER_WRITE", 30));

    assertThat(result.leaseId()).isEqualTo(existing.getLeaseId());
    verify(events, never()).save(any());
  }

  @Test
  void renewRequiresTheLeaseOwner() {
    when(leases.findByIdForUpdate("sfl_1234567890abcdef"))
        .thenReturn(Optional.of(activeLease(Instant.now(), 30)));

    assertThatThrownBy(
            () ->
                service.renew(
                    SESSION_ID,
                    "sfl_1234567890abcdef",
                    TENANT_ID,
                    "different-adapter",
                    "idem-renew",
                    new RenewSafetyLeaseRequest(30)))
        .isInstanceOf(SessionSafetyLeaseApplicationService.SafetyLeaseNotFoundException.class);
  }

  @Test
  void expiryTransitionsDurableStateAndAppendsEvent() {
    var due = activeLease(Instant.now().minusSeconds(60), 5);
    when(leases.lockExpiredLeaseIds(any(), anyInt())).thenReturn(List.of(due.getLeaseId()));
    when(leases.findById(due.getLeaseId())).thenReturn(Optional.of(due));

    assertThat(service.expireDueLeases()).isEqualTo(1);
    assertThat(due.getState()).isEqualTo("EXPIRED");
    var event = ArgumentCaptor.forClass(SessionSafetyLeaseEventEntity.class);
    verify(events).save(event.capture());
    assertThat(event.getValue().getEventType()).isEqualTo("EXPIRED");
  }

  @Test
  void activeLifecycleOperationRejectsNewApplicationWork() {
    var operation = mock(ExclusiveOperationEntity.class);
    when(operation.getMode()).thenReturn("HIBERNATE");
    when(operations.findBySessionIdAndState(SESSION_ID, "ACTIVE"))
        .thenReturn(Optional.of(operation));

    assertThatThrownBy(
            () ->
                service.acquire(
                    SESSION_ID,
                    TENANT_ID,
                    ACTOR_ID,
                    "idem-blocked",
                    new CreateSafetyLeaseRequest(CRITICAL_TRANSACTION, "LEDGER_WRITE", 30)))
        .isInstanceOf(SessionSafetyLeaseApplicationService.SafetyLeaseRejectedException.class)
        .hasMessage("SAFETY_LEASE_BLOCKED_BY_HIBERNATE");
  }

  @Test
  void diagnosticListIsBoundedAndReturnsTheAuthoritativeTotal() {
    var lease = activeLease(Instant.now(), 30);
    when(leases.findAllBySessionIdOrderByAcquiredAtDesc(anyString(), any(Pageable.class)))
        .thenReturn(List.of(lease));
    when(leases.countBySessionId(SESSION_ID)).thenReturn(321L);

    var result = service.list(SESSION_ID, TENANT_ID, 1_000);

    assertThat(result.items()).hasSize(1);
    assertThat(result.total()).isEqualTo(321);
    var page = ArgumentCaptor.forClass(Pageable.class);
    verify(leases).findAllBySessionIdOrderByAcquiredAtDesc(anyString(), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
  }

  private static SessionSafetyLeaseEntity activeLease(Instant acquiredAt, int ttlSeconds) {
    return new SessionSafetyLeaseEntity(
        "sfl_1234567890abcdef",
        SESSION_ID,
        TENANT_ID,
        7,
        "CRITICAL_TRANSACTION",
        "LEDGER_WRITE",
        ACTOR_ID,
        acquiredAt,
        acquiredAt.plusSeconds(ttlSeconds));
  }

  private static SessionContext session(SessionState state) {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
        TENANT_ID,
        "profile-a",
        "node-a",
        "runtime-a",
        "isolation-a",
        "proxy-a",
        3,
        7,
        1,
        1,
        ResourceClass.L2,
        state,
        "policy-hash",
        now,
        now);
  }
}
