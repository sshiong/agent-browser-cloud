package io.browsercloud.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiIdempotencyJpaRepository extends JpaRepository<ApiIdempotencyEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO api_idempotency_records (
              record_id, tenant_id, operation_type, idempotency_key,
              request_hash, resource_id, created_at
          ) VALUES (
              :recordId, :tenantId, :operationType, :idempotencyKey,
              :requestHash, :resourceId, :createdAt
          )
          ON CONFLICT (tenant_id, operation_type, idempotency_key) DO NOTHING
          """,
      nativeQuery = true)
  int claim(
      @Param("recordId") String recordId,
      @Param("tenantId") String tenantId,
      @Param("operationType") String operationType,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash,
      @Param("resourceId") String resourceId,
      @Param("createdAt") Instant createdAt);

  Optional<ApiIdempotencyEntity> findByTenantIdAndOperationTypeAndIdempotencyKey(
      String tenantId, String operationType, String idempotencyKey);
}
