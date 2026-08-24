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

  /**
   * Cross-Node restore requires a target that understands StartRuntimeCommand's generation floor.
   *
   * <p>Keep this predicate in PostgreSQL instead of filtering the generic 64-row candidate window
   * in memory. During a rolling upgrade, an arbitrarily large N-1 fleet must not hide a compatible
   * target behind the query limit.
   */
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
            AND labels->>'startRuntimeGenerationFloor' = 'v1'
          ORDER BY
            (CAST(reserved_memory_mib AS numeric) / certified_memory_mib) ASC,
            active_sessions ASC,
            node_id ASC
          FOR UPDATE SKIP LOCKED
          LIMIT 64
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> lockMigrationPlacementCandidates(
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

  @Query(
      value =
          """
          SELECT *
          FROM browser_nodes
          WHERE node_id = :nodeId
            AND lifecycle_state = 'READY'
            AND last_heartbeat_at >= :freshAfter
            AND labels->>'agentBrowserFiles' = 'cdp-file-v1'
          """,
      nativeQuery = true)
  Optional<BrowserNodeEntity> findAgentBrowserFileNode(
      @Param("nodeId") String nodeId, @Param("freshAfter") Instant freshAfter);

  @Query(
      value =
          """
          SELECT *
          FROM browser_nodes
          WHERE lifecycle_state = 'READY'
            AND admission_state = 'OPEN'
            AND pressure_state = 'NORMAL'
            AND last_heartbeat_at >= :freshAfter
            AND labels->>'profileExport' = 'presigned-checkpoint-v1'
          ORDER BY active_sessions ASC, last_heartbeat_at DESC, node_id ASC
          LIMIT 16
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> findProfileExportCandidates(@Param("freshAfter") Instant freshAfter);

  @Query(
      value =
          """
          SELECT *
          FROM browser_nodes
          WHERE (:region IS NULL OR region = :region)
            AND lifecycle_state = 'READY'
            AND admission_state = 'OPEN'
            AND pressure_state = 'NORMAL'
            AND last_heartbeat_at >= :freshAfter
            AND labels->>'proxyColdProbe' = 'network-helper-v1'
          ORDER BY active_sessions ASC, last_heartbeat_at DESC, node_id ASC
          LIMIT 16
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> findProxyColdProbeCandidates(
      @Param("region") String region, @Param("freshAfter") Instant freshAfter);

  @Query(
      value =
          """
          SELECT node.* FROM browser_nodes node
          WHERE LOWER(
            node.node_id || ' ' ||
            node.region || ' ' ||
            node.lifecycle_state || ' ' ||
            node.admission_state || ' ' ||
            node.pressure_state
          ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
          ORDER BY node.updated_at DESC
          """,
      nativeQuery = true)
  List<BrowserNodeEntity> searchAll(@Param("query") String query, Pageable pageable);
}
