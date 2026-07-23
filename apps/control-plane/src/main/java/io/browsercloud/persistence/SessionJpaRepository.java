package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

/** Session JPA Repository。 */
@Repository
public interface SessionJpaRepository extends JpaRepository<SessionEntity, String> {

  List<SessionEntity> findByTenantId(String tenantId);

  List<SessionEntity> findByTenantIdAndState(String tenantId, String state);

  Page<SessionEntity> findAllByTenantId(String tenantId, Pageable pageable);

  Page<SessionEntity> findAllByTenantIdAndState(String tenantId, String state, Pageable pageable);

  long countByTenantId(String tenantId);

  long countByTenantIdAndState(String tenantId, String state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<SessionEntity> findWithLockById(String id);
}
