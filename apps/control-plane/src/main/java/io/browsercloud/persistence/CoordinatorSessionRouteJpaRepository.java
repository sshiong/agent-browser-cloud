package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinatorSessionRouteJpaRepository
    extends JpaRepository<CoordinatorSessionRouteEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO coordinator_session_routes (
              session_id, tenant_id, route_epoch, virtual_partition, shard_id, bound_at, updated_at
          ) VALUES (
              :sessionId, :tenantId, :routeEpoch, :virtualPartition, :shardId, :now, :now
          )
          ON CONFLICT (session_id) DO NOTHING
          """,
      nativeQuery = true)
  int bindIfAbsent(
      @Param("sessionId") String sessionId,
      @Param("tenantId") String tenantId,
      @Param("routeEpoch") long routeEpoch,
      @Param("virtualPartition") int virtualPartition,
      @Param("shardId") int shardId,
      @Param("now") Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select route from CoordinatorSessionRouteEntity route where route.sessionId = :sessionId")
  Optional<CoordinatorSessionRouteEntity> findForUpdate(@Param("sessionId") String sessionId);

  @Query(
      """
      select route
        from CoordinatorSessionRouteEntity route
       where route.tenantId = :tenantId
         and route.routeEpoch < :targetRouteEpoch
       order by route.sessionId
      """)
  List<CoordinatorSessionRouteEntity> findPending(
      @Param("tenantId") String tenantId,
      @Param("targetRouteEpoch") long targetRouteEpoch,
      Pageable pageable);

  long countByTenantId(String tenantId);

  long countByTenantIdAndRouteEpoch(String tenantId, long routeEpoch);
}
