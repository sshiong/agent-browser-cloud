package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowserNodeJpaRepository extends JpaRepository<BrowserNodeEntity, String> {

  List<BrowserNodeEntity> findAllByOrderByNodeIdAsc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select node from BrowserNodeEntity node where node.nodeId = :nodeId")
  Optional<BrowserNodeEntity> findForUpdate(@Param("nodeId") String nodeId);

  @Query(
      value =
          """
          SELECT *
          FROM browser_nodes
          WHERE region = :region
            AND lifecycle_state = 'READY'
            AND admission_state = 'OPEN'
            AND pressure_state = 'NORMAL'
            AND last_heartbeat_at >= :freshAfter
          ORDER BY
            (CAST(reserved_memory_mib AS numeric) / certified_memory_mib) ASC,
            active_sessions ASC,
            node_id ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 64
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> lockPlacementCandidates(
      @Param("region") String region, @Param("freshAfter") Instant freshAfter);

  @Query(
      value =
          """
          SELECT *
          FROM browser_nodes
          WHERE lifecycle_state = 'READY'
            AND admission_state = 'OPEN'
            AND pressure_state = 'NORMAL'
            AND last_heartbeat_at >= :freshAfter
            AND labels->>'profileImport' = 'checkpoint-stream-v1'
          ORDER BY
            (CAST(reserved_memory_mib AS numeric) / certified_memory_mib) ASC,
            active_sessions ASC,
            node_id ASC
          LIMIT 16
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> findProfileImportCandidates(@Param("freshAfter") Instant freshAfter);
}
