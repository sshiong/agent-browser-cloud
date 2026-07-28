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

public interface SessionSafetyLeaseJpaRepository
    extends JpaRepository<SessionSafetyLeaseEntity, String> {

  List<SessionSafetyLeaseEntity> findAllBySessionIdOrderByAcquiredAtDesc(
      String sessionId, Pageable pageable);

  long countBySessionId(String sessionId);

  List<SessionSafetyLeaseEntity> findAllBySessionIdAndContextEpochAndState(
      String sessionId, long contextEpoch, String state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select lease from SessionSafetyLeaseEntity lease where lease.leaseId = :leaseId")
  Optional<SessionSafetyLeaseEntity> findByIdForUpdate(@Param("leaseId") String leaseId);

  @Query(
      value =
          """
          SELECT lease_id
          FROM session_safety_leases
          WHERE state = 'ACTIVE' AND expires_at <= :now
          ORDER BY expires_at
          FOR UPDATE SKIP LOCKED
          LIMIT :limit
          """,
      nativeQuery = true)
  List<String> lockExpiredLeaseIds(@Param("now") Instant now, @Param("limit") int limit);
}
