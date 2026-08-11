package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChallengeEventJpaRepository extends JpaRepository<ChallengeEventEntity, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select event from ChallengeEventEntity event where event.challengeEventId = :eventId and event.tenantId = :tenantId")
  Optional<ChallengeEventEntity> findForUpdate(
      @Param("eventId") String eventId, @Param("tenantId") String tenantId);

  @Query(
      """
      select event from ChallengeEventEntity event
      where event.tenantId = :tenantId and event.sessionId = :sessionId
        and event.contextEpoch = :contextEpoch and event.stateVersion = :stateVersion
        and event.targetRevision = :targetRevision and event.suspectedType = :suspectedType
        and ((:targetRef is null and event.targetRef is null) or event.targetRef = :targetRef)
        and event.status in ('SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'TAKEOVER_REQUIRED')
      """)
  Optional<ChallengeEventEntity> findDuplicate(
      @Param("tenantId") String tenantId,
      @Param("sessionId") String sessionId,
      @Param("contextEpoch") long contextEpoch,
      @Param("stateVersion") long stateVersion,
      @Param("targetRevision") long targetRevision,
      @Param("suspectedType") String suspectedType,
      @Param("targetRef") String targetRef);

  List<ChallengeEventEntity> findAllByTenantIdAndSessionIdOrderByDetectedAtDescChallengeEventIdDesc(
      String tenantId, String sessionId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select event from ChallengeEventEntity event
      where event.tenantId = :tenantId and event.sessionId = :sessionId
        and event.status in ('SUSPECTED', 'CONFIRMED', 'TAKEOVER_REQUIRED')
        and (event.contextEpoch <> :contextEpoch or event.stateVersion < :stateVersion)
      """)
  List<ChallengeEventEntity> findSupersededForUpdate(
      @Param("tenantId") String tenantId,
      @Param("sessionId") String sessionId,
      @Param("contextEpoch") long contextEpoch,
      @Param("stateVersion") long stateVersion);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select event from ChallengeEventEntity event
      where event.status in ('SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'TAKEOVER_REQUIRED')
        and event.expiresAt <= :now
      order by event.expiresAt
      """)
  List<ChallengeEventEntity> findExpiredForUpdate(@Param("now") Instant now, Pageable pageable);
}
