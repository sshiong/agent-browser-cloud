package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KeyRotationRequestJpaRepository
    extends JpaRepository<KeyRotationRequestEntity, String> {

  List<KeyRotationRequestEntity> findAllByTenantIdOrderByRequestedAtDesc(String tenantId);

  boolean existsByKeyScopeAndOldKeyIdAndStateIn(
      String keyScope, String oldKeyId, List<String> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select request from KeyRotationRequestEntity request "
          + "where request.rotationId = :rotationId and request.tenantId = :tenantId")
  Optional<KeyRotationRequestEntity> findForUpdate(
      @Param("rotationId") String rotationId, @Param("tenantId") String tenantId);
}
