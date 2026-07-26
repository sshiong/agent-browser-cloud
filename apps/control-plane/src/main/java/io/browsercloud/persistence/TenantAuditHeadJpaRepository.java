package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantAuditHeadJpaRepository extends JpaRepository<TenantAuditHeadEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO tenant_audit_heads(tenant_id, sequence_no, updated_at)
          VALUES (:tenantId, 0, now())
          ON CONFLICT (tenant_id) DO NOTHING
          """,
      nativeQuery = true)
  int ensure(@Param("tenantId") String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select head from TenantAuditHeadEntity head where head.tenantId = :tenantId")
  Optional<TenantAuditHeadEntity> findForUpdate(@Param("tenantId") String tenantId);
}
