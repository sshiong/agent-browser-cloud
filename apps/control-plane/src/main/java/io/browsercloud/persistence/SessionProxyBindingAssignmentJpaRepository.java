package io.browsercloud.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionProxyBindingAssignmentJpaRepository
    extends JpaRepository<SessionProxyBindingAssignmentEntity, String> {

  Optional<SessionProxyBindingAssignmentEntity> findBySessionIdAndTenantId(
      String sessionId, String tenantId);

  List<SessionProxyBindingAssignmentEntity> findAllByTenantIdAndSessionIdIn(
      String tenantId, Collection<String> sessionIds);

  boolean existsByTenantIdAndBindingProfileId(String tenantId, String bindingProfileId);

  @Query(
      value =
          """
          SELECT count(*)
          FROM session_proxy_binding_assignments assignment
          JOIN sessions session
            ON session.id = assignment.session_id
           AND session.tenant_id = assignment.tenant_id
          WHERE assignment.tenant_id = :tenantId
            AND assignment.provider_id = :providerId
            AND assignment.credential_ref = :credentialRef
            AND session.state NOT IN ('TERMINATED', 'FAILED')
          """,
      nativeQuery = true)
  long countActiveProviderReservations(String tenantId, String providerId, String credentialRef);
}
