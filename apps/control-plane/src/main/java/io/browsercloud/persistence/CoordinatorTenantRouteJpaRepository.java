package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinatorTenantRouteJpaRepository
    extends JpaRepository<CoordinatorTenantRouteEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO coordinator_tenant_routes (tenant_id, created_at, updated_at)
          VALUES (:tenantId, :now, :now)
          ON CONFLICT (tenant_id) DO NOTHING
          """,
      nativeQuery = true)
  int ensure(@Param("tenantId") String tenantId, @Param("now") Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select route from CoordinatorTenantRouteEntity route where route.tenantId = :tenantId")
  Optional<CoordinatorTenantRouteEntity> findForUpdate(@Param("tenantId") String tenantId);
}
