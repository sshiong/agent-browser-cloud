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

  Page<SessionEntity> findAllByTenantId(String tenantId, Pageable pageable);

  Page<SessionEntity> findAllByTenantIdAndState(String tenantId, String state, Pageable pageable);

  @Query(
      value =
          """
          SELECT s.* FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND (
              LOWER(s.id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.profile_id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.region) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.resource_class) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(CAST(s.metadata AS text)) LIKE LOWER(CONCAT('%', :query, '%'))
            )
          ORDER BY s.created_at DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND (
              LOWER(s.id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.profile_id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.region) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.resource_class) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(CAST(s.metadata AS text)) LIKE LOWER(CONCAT('%', :query, '%'))
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
              LOWER(s.id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.profile_id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.region) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.resource_class) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(CAST(s.metadata AS text)) LIKE LOWER(CONCAT('%', :query, '%'))
            )
          ORDER BY s.created_at DESC
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM sessions s
          WHERE s.tenant_id = :tenantId
            AND s.state = :state
            AND (
              LOWER(s.id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.profile_id) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.region) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(s.resource_class) LIKE LOWER(CONCAT('%', :query, '%'))
              OR LOWER(CAST(s.metadata AS text)) LIKE LOWER(CONCAT('%', :query, '%'))
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

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<SessionEntity> findWithLockById(String id);
}
