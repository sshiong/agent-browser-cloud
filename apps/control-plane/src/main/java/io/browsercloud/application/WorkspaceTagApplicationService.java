package io.browsercloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.WorkspaceTagModels.TagSessionView;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagListResponse;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagRequest;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagSummary;
import io.browsercloud.api.WorkspaceTagModels.WorkspaceTagView;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.SessionTagAssignmentEntity;
import io.browsercloud.persistence.SessionTagAssignmentJpaRepository;
import io.browsercloud.persistence.WorkspaceTagEntity;
import io.browsercloud.persistence.WorkspaceTagJpaRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceTagApplicationService {

  private final WorkspaceTagJpaRepository tags;
  private final SessionTagAssignmentJpaRepository assignments;
  private final SessionJpaRepository sessions;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final ObjectMapper mapper;

  public WorkspaceTagApplicationService(
      WorkspaceTagJpaRepository tags,
      SessionTagAssignmentJpaRepository assignments,
      SessionJpaRepository sessions,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      ObjectMapper mapper) {
    this.tags = tags;
    this.assignments = assignments;
    this.sessions = sessions;
    this.idempotency = idempotency;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public WorkspaceTagListResponse list(String tenantId) {
    var items =
        tags.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream().map(this::toView).toList();
    var tenantSessions =
        sessions.findByTenantId(tenantId).stream()
            .sorted(
                java.util.Comparator.comparing(SessionEntity::getCreatedAt)
                    .reversed()
                    .thenComparing(SessionEntity::getId))
            .map(this::toSessionView)
            .toList();
    return new WorkspaceTagListResponse(items, tenantSessions, items.size());
  }

  @Transactional
  public WorkspaceTagView create(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      WorkspaceTagRequest request) {
    var candidate = newId("tag_");
    var tagId = idempotency.claimWorkspaceTagCreate(tenantId, idempotencyKey, request, candidate);
    if (!candidate.equals(tagId)) {
      return toView(require(tagId, tenantId));
    }
    rejectDuplicateName(tenantId, request.name(), null);
    var now = Instant.now();
    var tag =
        persist(
            new WorkspaceTagEntity(
                tagId,
                tenantId,
                request.name(),
                request.description(),
                request.color(),
                actorId,
                now));
    appendAudit(
        tenantId,
        actorId,
        tagId,
        "WORKSPACE_TAG_CREATED",
        requestId,
        Map.of("name", tag.getName()));
    return toView(tag);
  }

  @Transactional
  public WorkspaceTagView update(
      String tenantId,
      String actorId,
      String tagId,
      String idempotencyKey,
      String requestId,
      WorkspaceTagRequest request) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceTagMutation(
            tenantId, tagId, "UPDATE", idempotencyKey, request, candidateMutation);
    var tag = require(tagId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(tag);
    }
    rejectDuplicateName(tenantId, request.name(), tagId);
    tag.update(request.name(), request.description(), request.color(), Instant.now());
    persist(tag);
    appendAudit(
        tenantId,
        actorId,
        tagId,
        "WORKSPACE_TAG_UPDATED",
        requestId,
        Map.of("name", tag.getName()));
    return toView(tag);
  }

  @Transactional
  public void delete(
      String tenantId, String actorId, String tagId, String idempotencyKey, String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceTagMutation(
            tenantId, tagId, "DELETE", idempotencyKey, tagId, candidateMutation);
    var tag = tags.findByTagIdAndTenantId(tagId, tenantId);
    if (!candidateMutation.equals(mutation) || tag.isEmpty()) {
      return;
    }
    var released =
        assignments.findAllByTenantIdAndTagIdOrderByAssignedAtDesc(tenantId, tagId).size();
    tags.delete(tag.orElseThrow());
    appendAudit(
        tenantId,
        actorId,
        tagId,
        "WORKSPACE_TAG_DELETED",
        requestId,
        Map.of("releasedSessions", released));
  }

  @Transactional
  public WorkspaceTagView assign(
      String tenantId,
      String actorId,
      String tagId,
      String sessionId,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceTagMutation(
            tenantId, tagId, "ASSIGN:" + sessionId, idempotencyKey, sessionId, candidateMutation);
    var tag = require(tagId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(tag);
    }
    requireSession(sessionId, tenantId);
    if (assignments.insertIfAbsent(
            newId("sta_"), tenantId, sessionId, tagId, actorId, Instant.now())
        == 1) {
      appendAudit(
          tenantId,
          actorId,
          tagId,
          "SESSION_TAG_ASSIGNED",
          requestId,
          Map.of("sessionId", sessionId));
    }
    return toView(tag);
  }

  @Transactional
  public WorkspaceTagView unassign(
      String tenantId,
      String actorId,
      String tagId,
      String sessionId,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimWorkspaceTagMutation(
            tenantId, tagId, "UNASSIGN:" + sessionId, idempotencyKey, sessionId, candidateMutation);
    var tag = require(tagId, tenantId);
    if (!candidateMutation.equals(mutation)) {
      return toView(tag);
    }
    requireSession(sessionId, tenantId);
    assignments
        .findByTenantIdAndTagIdAndSessionId(tenantId, tagId, sessionId)
        .ifPresent(
            assignment -> {
              assignments.delete(assignment);
              appendAudit(
                  tenantId,
                  actorId,
                  tagId,
                  "SESSION_TAG_UNASSIGNED",
                  requestId,
                  Map.of("sessionId", sessionId));
            });
    return toView(tag);
  }

  @Transactional
  public void assignInitial(
      String tenantId,
      String actorId,
      String sessionId,
      List<String> requestedTagIds,
      String requestId) {
    var tagIds = normalizeTagIds(requestedTagIds);
    if (tagIds.isEmpty()) {
      return;
    }
    requireSession(sessionId, tenantId);
    var selected = requireAll(tenantId, tagIds);
    var now = Instant.now();
    for (var tag : selected) {
      assignments.insertIfAbsent(newId("sta_"), tenantId, sessionId, tag.getTagId(), actorId, now);
    }
    appendAudit(
        tenantId,
        actorId,
        sessionId,
        "SESSION_INITIAL_TAGS_ASSIGNED",
        requestId,
        Map.of("tagIds", tagIds));
  }

  @Transactional(readOnly = true)
  public List<WorkspaceTagSummary> summariesForSession(String tenantId, String sessionId) {
    var tagIds =
        assignments.findAllByTenantIdAndSessionIdOrderByAssignedAtAsc(tenantId, sessionId).stream()
            .map(SessionTagAssignmentEntity::getTagId)
            .toList();
    if (tagIds.isEmpty()) {
      return List.of();
    }
    return tags.findAllByTenantIdAndTagIdInOrderByNameAsc(tenantId, tagIds).stream()
        .map(tag -> new WorkspaceTagSummary(tag.getTagId(), tag.getName(), tag.getColor()))
        .toList();
  }

  private List<WorkspaceTagEntity> requireAll(String tenantId, List<String> tagIds) {
    var selected = tags.findAllByTenantIdAndTagIdInOrderByNameAsc(tenantId, tagIds);
    var found = selected.stream().map(WorkspaceTagEntity::getTagId).collect(Collectors.toSet());
    var missing = tagIds.stream().filter(tagId -> !found.contains(tagId)).toList();
    if (!missing.isEmpty()) {
      throw new WorkspaceTagNotFoundException(missing.getFirst());
    }
    return selected;
  }

  private WorkspaceTagEntity require(String tagId, String tenantId) {
    return tags.findByTagIdAndTenantId(tagId, tenantId)
        .orElseThrow(() -> new WorkspaceTagNotFoundException(tagId));
  }

  private WorkspaceTagEntity persist(WorkspaceTagEntity tag) {
    try {
      return tags.saveAndFlush(tag);
    } catch (DataIntegrityViolationException exception) {
      throw new WorkspaceTagRejectedException("TAG_NAME_ALREADY_EXISTS");
    }
  }

  private SessionEntity requireSession(String sessionId, String tenantId) {
    var session =
        sessions.findById(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    if (!tenantId.equals(session.getTenantId())) {
      throw new SessionNotFoundException(sessionId);
    }
    return session;
  }

  private void rejectDuplicateName(String tenantId, String name, String currentTagId) {
    tags.findAllByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
        .filter(tag -> !tag.getTagId().equals(currentTagId))
        .filter(tag -> tag.getName().equalsIgnoreCase(name.strip()))
        .findFirst()
        .ifPresent(
            ignored -> {
              throw new WorkspaceTagRejectedException("TAG_NAME_ALREADY_EXISTS");
            });
  }

  private WorkspaceTagView toView(WorkspaceTagEntity tag) {
    var assignmentRows =
        assignments.findAllByTenantIdAndTagIdOrderByAssignedAtDesc(
            tag.getTenantId(), tag.getTagId());
    var sessionById =
        sessions
            .findAllById(
                assignmentRows.stream()
                    .map(SessionTagAssignmentEntity::getSessionId)
                    .distinct()
                    .toList())
            .stream()
            .filter(session -> tag.getTenantId().equals(session.getTenantId()))
            .collect(Collectors.toMap(SessionEntity::getId, Function.identity()));
    var members =
        assignmentRows.stream()
            .map(row -> sessionById.get(row.getSessionId()))
            .filter(java.util.Objects::nonNull)
            .map(this::toSessionView)
            .toList();
    return new WorkspaceTagView(
        tag.getTagId(),
        tag.getName(),
        tag.getDescription(),
        tag.getColor(),
        members,
        members.size(),
        tag.getCreatedBy(),
        tag.getCreatedAt(),
        tag.getUpdatedAt());
  }

  private TagSessionView toSessionView(SessionEntity session) {
    return new TagSessionView(
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
      String resourceId,
      String action,
      String requestId,
      Map<String, Object> details) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "WORKSPACE_TAG",
            "USER",
            actorId,
            "WORKSPACE_TAG",
            resourceId,
            action,
            "COMMITTED",
            details,
            requestId));
  }

  private static List<String> normalizeTagIds(List<String> requestedTagIds) {
    if (requestedTagIds == null || requestedTagIds.isEmpty()) {
      return List.of();
    }
    return List.copyOf(new LinkedHashSet<>(requestedTagIds));
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class WorkspaceTagNotFoundException extends RuntimeException {
    public WorkspaceTagNotFoundException(String tagId) {
      super(tagId);
    }
  }

  public static final class WorkspaceTagRejectedException extends RuntimeException {
    public WorkspaceTagRejectedException(String reason) {
      super(reason);
    }
  }
}
