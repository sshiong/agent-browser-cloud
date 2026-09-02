package io.browsercloud.infrastructure;

import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Dynamic, parameterized PostgreSQL query for Group/Tag-aware Session list filtering. */
@Repository
public class SessionFilteredQueryRepository {

  private final EntityManager entityManager;

  public SessionFilteredQueryRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public List<SessionEntity> list(
      String tenantId,
      SessionState state,
      String query,
      SessionListFilter filter,
      int limit,
      int offset) {
    var specification = build(tenantId, state, query, filter);
    var nativeQuery =
        entityManager.createNativeQuery(
            "SELECT session.* " + specification.fromAndWhere() + specification.orderBy(),
            SessionEntity.class);
    specification.bind(nativeQuery);
    nativeQuery.setFirstResult(offset);
    nativeQuery.setMaxResults(limit);
    @SuppressWarnings("unchecked")
    var rows = (List<SessionEntity>) nativeQuery.getResultList();
    return rows;
  }

  public long count(String tenantId, SessionState state, String query, SessionListFilter filter) {
    var specification = build(tenantId, state, query, filter);
    var nativeQuery =
        entityManager.createNativeQuery("SELECT COUNT(*) " + specification.fromAndWhere());
    specification.bind(nativeQuery);
    return ((Number) nativeQuery.getSingleResult()).longValue();
  }

  private QuerySpecification build(
      String tenantId, SessionState state, String rawQuery, SessionListFilter filter) {
    var sql =
        new StringBuilder(
            "FROM sessions session WHERE session.tenant_id = :tenantId AND session.deleted_at IS NULL");
    var normalizedQuery = rawQuery == null ? "" : rawQuery.strip();
    if (state != null) {
      sql.append(" AND session.state = :state");
    }
    if (!normalizedQuery.isEmpty()) {
      sql.append(
          """
           AND LOWER(
             session.id || ' ' ||
             session.profile_id || ' ' ||
             session.region || ' ' ||
             session.resource_class || ' ' ||
             session.state || ' ' ||
             COALESCE(session.metadata->>'displayName', '')
           ) LIKE LOWER(:query) ESCAPE '\\'
          """);
    }
    if (filter.groupId() != null && !filter.groupId().isBlank()) {
      sql.append(" AND session.group_id = :groupId");
    }
    if (!filter.tagIds().isEmpty()) {
      if (filter.tagMatch() == SessionListFilter.TagMatch.ALL) {
        sql.append(
            """
             AND (
               SELECT COUNT(DISTINCT assignment.tag_id)
                 FROM session_tag_assignments assignment
                WHERE assignment.tenant_id = :tenantId
                  AND assignment.session_id = session.id
                  AND assignment.tag_id IN (:tagIds)
             ) = :tagCount
            """);
      } else {
        sql.append(
            """
             AND EXISTS (
               SELECT 1
                 FROM session_tag_assignments assignment
                WHERE assignment.tenant_id = :tenantId
                  AND assignment.session_id = session.id
                  AND assignment.tag_id IN (:tagIds)
             )
            """);
      }
    }
    return new QuerySpecification(sql.toString(), tenantId, state, normalizedQuery, filter);
  }

  private static String escapeLikeLiteral(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record QuerySpecification(
      String fromAndWhere,
      String tenantId,
      SessionState state,
      String query,
      SessionListFilter filter) {

    String orderBy() {
      return " ORDER BY session.created_at DESC, session.id";
    }

    void bind(jakarta.persistence.Query nativeQuery) {
      nativeQuery.setParameter("tenantId", tenantId);
      if (state != null) {
        nativeQuery.setParameter("state", state.name());
      }
      if (!query.isEmpty()) {
        nativeQuery.setParameter("query", "%" + escapeLikeLiteral(query) + "%");
      }
      if (filter.groupId() != null && !filter.groupId().isBlank()) {
        nativeQuery.setParameter("groupId", filter.groupId());
      }
      if (!filter.tagIds().isEmpty()) {
        nativeQuery.setParameter("tagIds", filter.tagIds());
        if (filter.tagMatch() == SessionListFilter.TagMatch.ALL) {
          nativeQuery.setParameter("tagCount", filter.tagIds().size());
        }
      }
    }
  }
}
