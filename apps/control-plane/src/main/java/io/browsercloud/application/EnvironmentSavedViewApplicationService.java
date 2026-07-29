package io.browsercloud.application;

import static io.browsercloud.api.EnvironmentSavedViewModels.*;

import io.browsercloud.persistence.EnvironmentSavedViewEntity;
import io.browsercloud.persistence.EnvironmentSavedViewJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentSavedViewApplicationService {

  private static final Set<String> ADMIN_ROLES =
      Set.of("TENANT_ADMIN", "SECURITY_ADMIN", "PLATFORM_ADMIN");

  private final EnvironmentSavedViewJpaRepository views;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;

  public EnvironmentSavedViewApplicationService(
      EnvironmentSavedViewJpaRepository views,
      IdempotencyService idempotency,
      AuditApplicationService audit) {
    this.views = views;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public EnvironmentSavedViewListResponse list(String tenantId, String actorId) {
    var items = views.findVisible(tenantId, actorId).stream().map(this::toView).toList();
    return new EnvironmentSavedViewListResponse(items, items.size());
  }

  @Transactional
  public EnvironmentSavedViewView create(
      String tenantId,
      String actorId,
      Set<String> roles,
      String idempotencyKey,
      String requestId,
      CreateEnvironmentSavedViewRequest request) {
    requireScopePermission(request.scope(), roles);
    var candidate = newId("svw_");
    var savedViewId =
        idempotency.claimEnvironmentSavedViewCreate(
            tenantId, actorId, idempotencyKey, request, candidate);
    if (!candidate.equals(savedViewId)) {
      return toView(requireVisible(savedViewId, tenantId, actorId));
    }
    var now = Instant.now();
    var entity =
        persist(
            new EnvironmentSavedViewEntity(
                savedViewId,
                tenantId,
                actorId,
                request.scope(),
                request.name(),
                request.primaryView(),
                request.sessionState(),
                request.searchQuery(),
                request.showRuntimeColumn(),
                request.showContextColumn(),
                request.showOperationColumn(),
                now));
    appendAudit(tenantId, actorId, entity, "ENVIRONMENT_SAVED_VIEW_CREATED", requestId);
    return toView(entity);
  }

  @Transactional
  public EnvironmentSavedViewView update(
      String tenantId,
      String actorId,
      Set<String> roles,
      String savedViewId,
      String idempotencyKey,
      String requestId,
      UpdateEnvironmentSavedViewRequest request) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimEnvironmentSavedViewMutation(
            tenantId, savedViewId, actorId, "UPDATE", idempotencyKey, request, candidateMutation);
    var entity = require(savedViewId, tenantId);
    requireMutationPermission(entity, actorId, roles);
    if (!candidateMutation.equals(mutation)) {
      return toView(entity);
    }
    if (entity.getVersion() != request.expectedVersion()) {
      throw new EnvironmentSavedViewRejectedException("SAVED_VIEW_VERSION_MISMATCH");
    }
    entity.update(
        request.name(),
        request.primaryView(),
        request.sessionState(),
        request.searchQuery(),
        request.showRuntimeColumn(),
        request.showContextColumn(),
        request.showOperationColumn(),
        Instant.now());
    entity = persist(entity);
    appendAudit(tenantId, actorId, entity, "ENVIRONMENT_SAVED_VIEW_UPDATED", requestId);
    return toView(entity);
  }

  @Transactional
  public void delete(
      String tenantId,
      String actorId,
      Set<String> roles,
      String savedViewId,
      long expectedVersion,
      String idempotencyKey,
      String requestId) {
    var candidateMutation = newId("mut_");
    var mutation =
        idempotency.claimEnvironmentSavedViewMutation(
            tenantId,
            savedViewId,
            actorId,
            "DELETE",
            idempotencyKey,
            Map.of("expectedVersion", expectedVersion),
            candidateMutation);
    if (!candidateMutation.equals(mutation)) {
      return;
    }
    var entity = require(savedViewId, tenantId);
    requireMutationPermission(entity, actorId, roles);
    if (entity.getVersion() != expectedVersion) {
      throw new EnvironmentSavedViewRejectedException("SAVED_VIEW_VERSION_MISMATCH");
    }
    try {
      views.delete(entity);
      views.flush();
    } catch (OptimisticLockingFailureException exception) {
      throw new EnvironmentSavedViewRejectedException("SAVED_VIEW_VERSION_MISMATCH");
    }
    appendAudit(tenantId, actorId, entity, "ENVIRONMENT_SAVED_VIEW_DELETED", requestId);
  }

  private EnvironmentSavedViewEntity persist(EnvironmentSavedViewEntity entity) {
    try {
      return views.saveAndFlush(entity);
    } catch (OptimisticLockingFailureException exception) {
      throw new EnvironmentSavedViewRejectedException("SAVED_VIEW_VERSION_MISMATCH");
    } catch (DataIntegrityViolationException exception) {
      throw new EnvironmentSavedViewRejectedException("SAVED_VIEW_NAME_ALREADY_EXISTS");
    }
  }

  private EnvironmentSavedViewEntity require(String savedViewId, String tenantId) {
    return views
        .findBySavedViewIdAndTenantId(savedViewId, tenantId)
        .orElseThrow(EnvironmentSavedViewNotFoundException::new);
  }

  private EnvironmentSavedViewEntity requireVisible(
      String savedViewId, String tenantId, String actorId) {
    var entity = require(savedViewId, tenantId);
    if (entity.getScope() == SavedViewScope.PERSONAL && !entity.getOwnerActorId().equals(actorId)) {
      throw new EnvironmentSavedViewNotFoundException();
    }
    return entity;
  }

  private static void requireScopePermission(SavedViewScope scope, Set<String> roles) {
    if (scope == SavedViewScope.WORKSPACE && roles.stream().noneMatch(ADMIN_ROLES::contains)) {
      throw new AccessDeniedException("Workspace Saved Views require an administrator");
    }
  }

  private static void requireMutationPermission(
      EnvironmentSavedViewEntity entity, String actorId, Set<String> roles) {
    if (entity.getScope() == SavedViewScope.PERSONAL) {
      if (!entity.getOwnerActorId().equals(actorId)) {
        throw new EnvironmentSavedViewNotFoundException();
      }
      return;
    }
    requireScopePermission(entity.getScope(), roles);
  }

  private void appendAudit(
      String tenantId,
      String actorId,
      EnvironmentSavedViewEntity entity,
      String action,
      String requestId) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "ENVIRONMENT_SAVED_VIEW",
            "USER",
            actorId,
            "ENVIRONMENT_SAVED_VIEW",
            entity.getSavedViewId(),
            action,
            "COMMITTED",
            Map.of(
                "name",
                entity.getName(),
                "scope",
                entity.getScope().name(),
                "primaryView",
                entity.getPrimaryView().name(),
                "sessionState",
                entity.getSessionState() == null ? "" : entity.getSessionState().name(),
                "searchQueryHash",
                PromptSecurityService.sha256(entity.getSearchQuery()),
                "version",
                entity.getVersion()),
            requestId));
  }

  private EnvironmentSavedViewView toView(EnvironmentSavedViewEntity entity) {
    return new EnvironmentSavedViewView(
        entity.getSavedViewId(),
        entity.getName(),
        entity.getScope(),
        entity.getOwnerActorId(),
        entity.getPrimaryView(),
        entity.getSessionState(),
        entity.getSearchQuery(),
        entity.isShowRuntimeColumn(),
        entity.isShowContextColumn(),
        entity.isShowOperationColumn(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class EnvironmentSavedViewNotFoundException extends RuntimeException {}

  public static final class EnvironmentSavedViewRejectedException extends RuntimeException {
    public EnvironmentSavedViewRejectedException(String message) {
      super(message);
    }
  }
}
