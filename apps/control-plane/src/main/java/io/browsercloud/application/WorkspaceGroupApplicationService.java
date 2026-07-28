package io.browsercloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.WorkspaceGroupModels.GroupSessionView;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupListResponse;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupRequest;
import io.browsercloud.api.WorkspaceGroupModels.WorkspaceGroupView;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.resource.ExecutionEnvironment;
import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceGroupEntity;
import io.browsercloud.persistence.WorkspaceGroupJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceGroupApplicationService {

  private final WorkspaceGroupJpaRepository groups;
  private final SessionJpaRepository sessions;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final ObjectMapper mapper;

  public WorkspaceGroupApplicationService(
      WorkspaceGroupJpaRepository groups,
      SessionJpaRepository sessions,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      ObjectMapper mapper) {
    this.groups = groups;
    this.sessions = sessions;
    this.idempotency = idempotency;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public WorkspaceGroupListResponse list(String tenantId) {
    var items =
        groups.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream().map(this::toView).toList();
    var unassigned =
        sessions.findAllByTenantIdAndGroupIdIsNullOrderByCreatedAtDesc(tenantId).stream()
            .map(this::toSessionView)
            .toList();
    return new WorkspaceGroupListResponse(items, unassigned, items.size());
  }

  @Transactional
  public WorkspaceGroupView create(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      WorkspaceGroupRequest request,
      boolean platformAdmin) {
    requirePolicyPermission(request.defaultOnMaximumReached(), platformAdmin);
    var candidate = newId("grp_");
    var groupId =
        idempotency.claimWorkspaceGroupCreate(tenantId, idempotencyKey, request, candidate);
    if (!candidate.equals(groupId)) {
      return toView(require(groupId, tenantId));
    }
    rejectDuplicateName(tenantId, request.name(), null);
    var now = Instant.now();
    var group =
        groups.save(
            new WorkspaceGroupEntity(
                groupId,
                tenantId,
                request.name(),
                request.description(),
                request.color(),
                request.defaultOnMaximumReached(),
                request.defaultAllowMigration(),
                request.defaultAllowHibernate(),
                actorId,
                now));
    appendAudit(
        tenantId,
        actorId,
        groupId,
        "WORKSPACE_GROUP_CREATED",
        requestId,
        Map.of("name", group.getName()));
    return toView(group);
  }

  @Transactional
  public WorkspaceGroupView update(
      String tenantId,
      String actorId,
      String groupId,
      String idempotencyKey,
      String requestId,
      WorkspaceGroupRequest request,
      boolean platformAdmin) {
    requirePolicyPermission(request.defaultOnMaximumReached(), platformAdmin);
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceGroupMutation(
            tenantId, groupId, "UPDATE", idempotencyKey, request, candidateMutation);
    var group = require(groupId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(group);
    }
    rejectDuplicateName(tenantId, request.name(), groupId);
    group.update(
        request.name(),
        request.description(),
        request.color(),
        request.defaultOnMaximumReached(),
        request.defaultAllowMigration(),
        request.defaultAllowHibernate(),
        Instant.now());
    groups.save(group);
    appendAudit(
        tenantId,
        actorId,
        groupId,
        "WORKSPACE_GROUP_UPDATED",
        requestId,
        Map.of("name", group.getName()));
    return toView(group);
  }

  @Transactional
  public void delete(
      String tenantId, String actorId, String groupId, String idempotencyKey, String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceGroupMutation(
            tenantId, groupId, "DELETE", idempotencyKey, groupId, candidateMutation);
    var group = groups.findByGroupIdAndTenantId(groupId, tenantId);
    if (!candidateMutation.equals(mutation) || group.isEmpty()) {
      return;
    }
    var members = sessions.findAllByTenantIdAndGroupIdOrderByCreatedAtDesc(tenantId, groupId);
    members.forEach(session -> session.setGroupId(null));
    sessions.saveAll(members);
    groups.delete(group.orElseThrow());
    appendAudit(
        tenantId,
        actorId,
        groupId,
        "WORKSPACE_GROUP_DELETED",
        requestId,
        Map.of("releasedSessions", members.size()));
  }

  @Transactional
  public WorkspaceGroupView assign(
      String tenantId,
      String actorId,
      String groupId,
      String sessionId,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceGroupMutation(
            tenantId, groupId, "ASSIGN:" + sessionId, idempotencyKey, sessionId, candidateMutation);
    var group = require(groupId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(group);
    }
    var session = requireSession(sessionId, tenantId);
    session.setGroupId(groupId);
    session.setUpdatedAt(Instant.now());
    sessions.save(session);
    appendAudit(
        tenantId,
        actorId,
        groupId,
        "SESSION_GROUP_ASSIGNED",
        requestId,
        Map.of("sessionId", sessionId));
    return toView(group);
  }

  @Transactional
  public WorkspaceGroupView unassign(
      String tenantId,
      String actorId,
      String groupId,
      String sessionId,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceGroupMutation(
            tenantId,
            groupId,
            "UNASSIGN:" + sessionId,
            idempotencyKey,
            sessionId,
            candidateMutation);
    var group = require(groupId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(group);
    }
    var session = requireSession(sessionId, tenantId);
    if (!groupId.equals(session.getGroupId())) {
      throw new WorkspaceGroupRejectedException("SESSION_IS_NOT_ASSIGNED_TO_GROUP");
    }
    session.setGroupId(null);
    session.setUpdatedAt(Instant.now());
    sessions.save(session);
    appendAudit(
        tenantId,
        actorId,
        groupId,
        "SESSION_GROUP_UNASSIGNED",
        requestId,
        Map.of("sessionId", sessionId));
    return toView(group);
  }

  @Transactional(readOnly = true)
  public ResourcePolicyRequest resolvePolicy(
      String tenantId, String groupId, ResourcePolicyRequest explicitPolicy) {
    if (groupId == null || groupId.isBlank()) {
      return explicitPolicy;
    }
    var group = require(groupId, tenantId);
    if (explicitPolicy != null) {
      return explicitPolicy;
    }
    return new ResourcePolicyRequest(
        ResourcePolicyMode.AUTO,
        group.defaultOnMaximumReached(),
        group.isDefaultAllowMigration(),
        group.isDefaultAllowHibernate(),
        true,
        ExecutionEnvironment.SYSTEM_MANAGED,
        "standard-v1",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Transactional(readOnly = true)
  public void requireExists(String tenantId, String groupId) {
    if (groupId != null && !groupId.isBlank()) {
      require(groupId, tenantId);
    }
  }

  private WorkspaceGroupEntity require(String groupId, String tenantId) {
    return groups
        .findByGroupIdAndTenantId(groupId, tenantId)
        .orElseThrow(() -> new WorkspaceGroupNotFoundException(groupId));
  }

  private SessionEntity requireSession(String sessionId, String tenantId) {
    var session =
        sessions.findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    if (!tenantId.equals(session.getTenantId())) {
      throw new SessionNotFoundException(sessionId);
    }
    return session;
  }

  private void rejectDuplicateName(String tenantId, String name, String currentGroupId) {
    groups.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
        .filter(group -> !group.getGroupId().equals(currentGroupId))
        .filter(group -> group.getName().equalsIgnoreCase(name.strip()))
        .findFirst()
        .ifPresent(
            ignored -> {
              throw new WorkspaceGroupRejectedException("GROUP_NAME_ALREADY_EXISTS");
            });
  }

  private WorkspaceGroupView toView(WorkspaceGroupEntity group) {
    var members =
        sessions
            .findAllByTenantIdAndGroupIdOrderByCreatedAtDesc(
                group.getTenantId(), group.getGroupId())
            .stream()
            .map(this::toSessionView)
            .toList();
    return new WorkspaceGroupView(
        group.getGroupId(),
        group.getName(),
        group.getDescription(),
        group.getColor(),
        group.defaultOnMaximumReached(),
        group.isDefaultAllowMigration(),
        group.isDefaultAllowHibernate(),
        members,
        members.size(),
        group.getCreatedBy(),
        group.getCreatedAt(),
        group.getUpdatedAt());
  }

  private GroupSessionView toSessionView(SessionEntity session) {
    return new GroupSessionView(
        session.getId(),
        displayName(session),
        SessionState.valueOf(session.getState()),
        session.getRegion(),
        session.getUpdatedAt());
  }

  private String displayName(SessionEntity session) {
    try {
      var root = mapper.readTree(session.getMetadata());
      var displayName = root == null ? null : root.get("displayName");
      return displayName != null && displayName.isTextual() && !displayName.textValue().isBlank()
          ? displayName.textValue()
          : session.getId();
    } catch (Exception exception) {
      return session.getId();
    }
  }

  private void appendAudit(
      String tenantId,
      String actorId,
      String groupId,
      String action,
      String requestId,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "WORKSPACE_GROUP",
            "USER",
            actorId,
            "WORKSPACE_GROUP",
            groupId,
            action,
            "COMMITTED",
            details,
            requestId));
  }

  private static void requirePolicyPermission(MaximumReachedPolicy policy, boolean platformAdmin) {
    if (policy == MaximumReachedPolicy.TERMINATE_STRICT && !platformAdmin) {
      throw new WorkspaceGroupRejectedException("STRICT_POLICY_REQUIRES_PLATFORM_ADMIN");
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class WorkspaceGroupNotFoundException extends RuntimeException {
    public WorkspaceGroupNotFoundException(String groupId) {
      super("Workspace Group not found: " + groupId);
    }
  }

  public static final class WorkspaceGroupRejectedException extends RuntimeException {
    public WorkspaceGroupRejectedException(String reason) {
      super(reason);
    }
  }
}
