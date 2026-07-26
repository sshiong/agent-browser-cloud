package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowserPlacementJpaRepository
    extends JpaRepository<BrowserPlacementEntity, String> {

  List<BrowserPlacementEntity> findAllByNodeIdAndStateIn(String nodeId, Collection<String> states);

  @Query(
      value =
          """
          SELECT placement.*
          FROM browser_placements placement
          JOIN browser_nodes node ON node.node_id = placement.node_id
          WHERE node.pressure_state = 'CRITICAL'
            AND placement.state = 'ACTIVE'
          ORDER BY
            placement.unknown_extension_count DESC,
            placement.requested_resource_class ASC,
            placement.memory_request_mib DESC,
            placement.reserved_at DESC
          FOR UPDATE OF placement SKIP LOCKED
          LIMIT 1
          """,
      nativeQuery = true)
  Optional<BrowserPlacementEntity> claimPressureEvictionCandidate();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select placement from BrowserPlacementEntity placement where placement.sessionId = :sessionId")
  Optional<BrowserPlacementEntity> findForUpdate(@Param("sessionId") String sessionId);
}
