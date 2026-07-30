package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Session JPA Repository。 */
@Repository
public interface SessionJpaRepository extends JpaRepository<SessionEntity, String> {

  List<SessionEntity> findByTenantId(String tenantId);

  List<SessionEntity> findByTenantIdAndState(String tenantId, String state);

  List<SessionEntity> findAllByTenantIdAndGroupIdOrderByCreatedAtDesc(
      String tenantId, String groupId);

  List<SessionEntity> findAllByTenantIdAndGroupIdIsNullOrderByCreatedAtDesc(String tenantId);

  Page<SessionEntity> findAllByTenantId(String tenantId, Pageable pageable);

  Page<SessionEntity> findAllByTenantIdAndState(String tenantId, String state, Pageable pageable);

  @Query(
      value =
          """
          SELECT s.* FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND (
              LOWER(
                s.id || ' ' ||
                s.profile_id || ' ' ||
                s.region || ' ' ||
                s.resource_class || ' ' ||
                s.state || ' ' ||
                COALESCE(s.metadata->>'displayName', '')
              ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
            )
          ORDER BY s.created_at DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND (
              LOWER(
                s.id || ' ' ||
                s.profile_id || ' ' ||
                s.region || ' ' ||
                s.resource_class || ' ' ||
                s.state || ' ' ||
                COALESCE(s.metadata->>'displayName', '')
              ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
            )
          """,
      nativeQuery = true)
  Page<SessionEntity> searchAllByTenantId(
      @Param("tenantId") String tenantId, @Param("query") String query, Pageable pageable);

  @Query(
      value =
          """
          SELECT s.* FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND s.state = :state
            AND (
              LOWER(
                s.id || ' ' ||
                s.profile_id || ' ' ||
                s.region || ' ' ||
                s.resource_class || ' ' ||
                s.state || ' ' ||
                COALESCE(s.metadata->>'displayName', '')
              ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
            )
          ORDER BY s.created_at DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND s.state = :state
            AND (
              LOWER(
                s.id || ' ' ||
                s.profile_id || ' ' ||
                s.region || ' ' ||
                s.resource_class || ' ' ||
                s.state || ' ' ||
                COALESCE(s.metadata->>'displayName', '')
              ) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\'
            )
          """,
      nativeQuery = true)
  Page<SessionEntity> searchAllByTenantIdAndState(
      @Param("tenantId") String tenantId,
      @Param("state") String state,
      @Param("query") String query,
      Pageable pageable);

  long countByTenantId(String tenantId);

  long countByTenantIdAndState(String tenantId, String state);

  @Query(
      value =
          """
          SELECT s.id
            FROM sessions s
           WHERE s.tenant_id = :tenantId
             AND NOT EXISTS (
               SELECT 1
                 FROM coordinator_session_routes route
                WHERE route.session_id = s.id
             )
           ORDER BY s.id
          """,
      nativeQuery = true)
  List<String> findIdsMissingCoordinatorRoute(
      @Param("tenantId") String tenantId, Pageable pageable);

  @Query(
      value =
          """
          SELECT count(*)
            FROM sessions s
           WHERE s.tenant_id = :tenantId
             AND NOT EXISTS (
               SELECT 1
                 FROM coordinator_session_routes route
                WHERE route.session_id = s.id
             )
          """,
      nativeQuery = true)
  long countMissingCoordinatorRoute(@Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<SessionEntity> findWithLockById(String id);
}
