package io.browsercloud.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.exceptions.CoordinatorNotOwnerException;
import io.browsercloud.coordinator.exceptions.StaleCoordinatorTermException;
import io.browsercloud.persistence.CoordinatorOwnershipEntity;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoordinatorOwnershipServiceTest {

  @Mock private CoordinatorOwnershipJpaRepository repository;

  @Test
  void renewsTheCurrentOwnerWithoutAdvancingTerm() {
    var ownership = ownership("ses-1", "coordinator-a", 7);
    when(repository.findById("ses-1")).thenReturn(Optional.of(ownership));
    when(repository.heartbeatIfOwner(eq("ses-1"), eq("coordinator-a"), eq(1L), any(Instant.class)))
        .thenReturn(1);
    var service = new CoordinatorOwnershipService(repository, "coordinator-a", 30);

    assertThat(service.acquireSession("ses-1", 1)).isEqualTo(7);
    verify(repository)
        .heartbeatIfOwner(eq("ses-1"), eq("coordinator-a"), eq(1L), any(Instant.class));
  }

  @Test
  void returnsTheNewTermAfterAnExpiredOwnerIsReplaced() {
    var oldOwnership = ownership("ses-1", "coordinator-a", 2);
    var newOwnership = ownership("ses-1", "coordinator-b", 3);
    when(repository.findById("ses-1"))
        .thenReturn(Optional.of(oldOwnership))
        .thenReturn(Optional.of(newOwnership));
    when(repository.claimIfAbsentOrExpired(
            eq("ses-1"), eq("coordinator-b"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(1);
    var service = new CoordinatorOwnershipService(repository, "coordinator-b", 5);

    assertThat(service.acquireSession("ses-1", 1)).isEqualTo(3);
  }

  @Test
  void rejectsCommandsWhileAnotherLiveOwnerStillHoldsTheLease() {
    var ownership = ownership("ses-1", "coordinator-a", 2);
    when(repository.findById("ses-1")).thenReturn(Optional.of(ownership));
    var service = new CoordinatorOwnershipService(repository, "coordinator-b", 30);

    assertThatThrownBy(() -> service.acquireSession("ses-1", 1))
        .isInstanceOf(CoordinatorNotOwnerException.class);
  }

  @Test
  void rejectsNodeEventsWhoseTermIsNotOwnedByThisInstance() {
    var ownership = ownership("ses-1", "coordinator-b", 3);
    when(repository.findById("ses-1")).thenReturn(Optional.of(ownership));
    var service = new CoordinatorOwnershipService(repository, "coordinator-b", 30);

    assertThatThrownBy(() -> service.assertCurrentOwner("ses-1", 2, 1))
        .isInstanceOf(StaleCoordinatorTermException.class);
  }

  @Test
  void rejectsOwnerHeartbeatFromAnOlderRouteEpoch() {
    var ownership = ownership("ses-1", "coordinator-b", 4);
    ownership.setRouteEpoch(2);
    when(repository.findById("ses-1")).thenReturn(Optional.of(ownership));
    var service = new CoordinatorOwnershipService(repository, "coordinator-b", 30);

    assertThatThrownBy(() -> service.assertCurrentOwner("ses-1", 4, 1))
        .isInstanceOf(CoordinatorNotOwnerException.class);
  }

  private CoordinatorOwnershipEntity ownership(String sessionId, String owner, long term) {
    var ownership = new CoordinatorOwnershipEntity();
    ownership.setSessionId(sessionId);
    ownership.setCoordinatorOwner(owner);
    ownership.setCoordinatorTerm(term);
    ownership.setRouteEpoch(1);
    ownership.setOwnerHeartbeatAt(Instant.now());
    ownership.setClaimedAt(Instant.now());
    return ownership;
  }
}
