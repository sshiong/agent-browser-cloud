package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteDesktopParticipantJpaRepository
    extends JpaRepository<RemoteDesktopParticipantEntity, String> {
  List<RemoteDesktopParticipantEntity>
      findAllByTenantIdAndSessionIdAndContextEpochAndStateInOrderByObservedAtDescConnectionId(
          String tenantId, String sessionId, long contextEpoch, List<String> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select participant from RemoteDesktopParticipantEntity participant where participant.connectionId = :connectionId and participant.tenantId = :tenantId and participant.sessionId = :sessionId")
  Optional<RemoteDesktopParticipantEntity> findForUpdate(
      @Param("connectionId") String connectionId,
      @Param("tenantId") String tenantId,
      @Param("sessionId") String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select participant from RemoteDesktopParticipantEntity participant where participant.connectionId = :connectionId")
  Optional<RemoteDesktopParticipantEntity> findByIdForUpdate(
      @Param("connectionId") String connectionId);
}
