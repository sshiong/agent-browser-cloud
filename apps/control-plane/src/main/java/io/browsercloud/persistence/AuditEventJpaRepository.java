package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, String> {

  List<AuditEventEntity> findAllByTenantIdOrderBySequenceNoDesc(String tenantId, Pageable pageable);

  List<AuditEventEntity> findAllByTenantIdAndEventTypeOrderBySequenceNoDesc(
      String tenantId, String eventType, Pageable pageable);

  List<AuditEventEntity> findAllByTenantIdAndSessionIdOrderBySequenceNoDesc(
      String tenantId, String sessionId, Pageable pageable);

  List<AuditEventEntity> findAllByTenantIdAndSessionIdAndEventTypeOrderBySequenceNoDesc(
      String tenantId, String sessionId, String eventType, Pageable pageable);

  List<AuditEventEntity> findAllByTenantIdOrderBySequenceNoAsc(String tenantId);

  long countByTenantId(String tenantId);
}
