package io.browsercloud.application;

import io.browsercloud.api.WorkspaceSettingsModels.WorkspaceSettingsRequest;
import io.browsercloud.api.WorkspaceSettingsModels.WorkspaceSettingsView;
import io.browsercloud.persistence.WorkspaceSettingsEntity;
import io.browsercloud.persistence.WorkspaceSettingsJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceSettingsApplicationService {

  private static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";
  private static final String DEFAULT_REGION = "local";

  private final WorkspaceSettingsJpaRepository settings;
  private final RuntimeBuildPolicy runtimeBuildPolicy;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;
  private final String systemDefaultRuntimeBuildId;

  public WorkspaceSettingsApplicationService(
      WorkspaceSettingsJpaRepository settings,
      RuntimeBuildPolicy runtimeBuildPolicy,
      IdempotencyService idempotency,
      AuditApplicationService audit,
      @Value("${browser-node.default-runtime-build-id:runtime_local_chromium}")
          String systemDefaultRuntimeBuildId) {
    this.settings = settings;
    this.runtimeBuildPolicy = runtimeBuildPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.systemDefaultRuntimeBuildId = systemDefaultRuntimeBuildId;
  }

  @Transactional(readOnly = true)
  public WorkspaceSettingsView get(String tenantId) {
    return settings
        .findById(tenantId)
        .map(this::toView)
        .orElseGet(
            () ->
                new WorkspaceSettingsView(
                    DEFAULT_WORKSPACE_NAME,
                    systemDefaultRuntimeBuildId,
                    DEFAULT_REGION,
                    true,
                    "AUTO",
                    "PAUSE_AGENT",
                    "SYSTEM_DEFAULT",
                    null,
                    null,
                    0));
  }

  @Transactional(readOnly = true)
  public EffectiveWorkspaceSettings resolve(String tenantId) {
    return settings
        .findById(tenantId)
        .map(
            entity ->
                new EffectiveWorkspaceSettings(
                    entity.getDefaultRuntimeBuildId(),
                    entity.getDefaultRegion(),
                    entity.isDefaultHumanTakeoverEnabled()))
        .orElseGet(
            () ->
                new EffectiveWorkspaceSettings(systemDefaultRuntimeBuildId, DEFAULT_REGION, true));
  }

  @Transactional
  public WorkspaceSettingsView update(
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      WorkspaceSettingsRequest request) {
    runtimeBuildPolicy.requireApproved(request.defaultRuntimeBuildId());
    var candidateMutationId = newId("mut_");
    var mutationId =
        idempotency.claimWorkspaceSettingsUpdate(
            tenantId, idempotencyKey, request, candidateMutationId);
    if (!candidateMutationId.equals(mutationId)) {
      return get(tenantId);
    }
    var now = Instant.now();
    settings.upsert(
        tenantId,
        request.workspaceName().strip(),
        request.defaultRuntimeBuildId(),
        request.defaultRegion(),
        request.defaultHumanTakeoverEnabled(),
        actorId,
        now);
    settings.flush();
    var persisted =
        settings
            .findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("Workspace Settings update disappeared"));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            null,
            "WORKSPACE_SETTINGS",
            "USER",
            actorId,
            "WORKSPACE_SETTINGS",
            tenantId,
            "WORKSPACE_SETTINGS_UPDATED",
            "COMMITTED",
            Map.of(
                "workspaceName",
                persisted.getWorkspaceName(),
                "defaultRuntimeBuildId",
                persisted.getDefaultRuntimeBuildId(),
                "defaultRegion",
                persisted.getDefaultRegion(),
                "defaultHumanTakeoverEnabled",
                persisted.isDefaultHumanTakeoverEnabled()),
            requestId));
    return toView(persisted);
  }

  private WorkspaceSettingsView toView(WorkspaceSettingsEntity entity) {
    return new WorkspaceSettingsView(
        entity.getWorkspaceName(),
        entity.getDefaultRuntimeBuildId(),
        entity.getDefaultRegion(),
        entity.isDefaultHumanTakeoverEnabled(),
        "AUTO",
        "PAUSE_AGENT",
        "WORKSPACE_OVERRIDE",
        entity.getUpdatedBy(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public record EffectiveWorkspaceSettings(
      String defaultRuntimeBuildId, String defaultRegion, boolean defaultHumanTakeoverEnabled) {}
}
