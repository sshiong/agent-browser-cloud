package io.browsercloud.coordinator;

import io.browsercloud.persistence.CoordinatorOwnershipEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Coordinator Ownership JPA Repository。 */
@Repository
public interface CoordinatorOwnershipJpaRepository
    extends JpaRepository<CoordinatorOwnershipEntity, String> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
                    INSERT INTO coordinator_ownership (
                        session_id, coordinator_owner, coordinator_term,
                        route_epoch, owner_heartbeat_at, claimed_at
                    ) VALUES (:sessionId, :coordinatorId, 1, :routeEpoch, :now, :now)
                    ON CONFLICT (session_id) DO UPDATE
                    SET coordinator_owner = EXCLUDED.coordinator_owner,
                        coordinator_term = coordinator_ownership.coordinator_term + 1,
                        route_epoch = EXCLUDED.route_epoch,
                        owner_heartbeat_at = EXCLUDED.owner_heartbeat_at,
                        claimed_at = EXCLUDED.claimed_at
                    WHERE coordinator_ownership.owner_heartbeat_at < :expiredBefore
                      AND coordinator_ownership.route_epoch <= :routeEpoch
                    """,
      nativeQuery = true)
  int claimIfAbsentOrExpired(
      String sessionId, String coordinatorId, long routeEpoch, Instant now, Instant expiredBefore);

  @Modifying
  @Query(
      """
            update CoordinatorOwnershipEntity ownership
               set ownership.ownerHeartbeatAt = :now
             where ownership.sessionId = :sessionId
               and ownership.coordinatorOwner = :coordinatorId
               and ownership.routeEpoch = :routeEpoch
            """)
  int heartbeatIfOwner(String sessionId, String coordinatorId, long routeEpoch, Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update CoordinatorOwnershipEntity ownership
         set ownership.routeEpoch = :routeEpoch,
             ownership.coordinatorTerm = ownership.coordinatorTerm + 1,
             ownership.ownerHeartbeatAt = :expiredAt
       where ownership.sessionId = :sessionId
         and ownership.routeEpoch < :routeEpoch
      """)
  int fenceRouteEpoch(String sessionId, long routeEpoch, Instant expiredAt);
}
