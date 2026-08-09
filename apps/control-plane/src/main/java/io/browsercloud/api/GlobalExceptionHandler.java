package io.browsercloud.api;

import io.browsercloud.application.AgentApplicationService.AgentTaskNotFoundException;
import io.browsercloud.application.AgentApplicationService.InvalidAgentTaskException;
import io.browsercloud.application.AgentExecutionService.AgentExecutionRejectedException;
import io.browsercloud.application.AgentHumanGovernanceService.HumanGovernanceException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.BusinessRecoveryStateUnavailableException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.BusinessRecoveryValidationNotFoundException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.ProviderEvidenceRejectedException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractApprovalNotFoundException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractApprovalRejectedException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractApprovalRequiredException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractNotFoundException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractRejectedException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.RecoveryContractVersionConflictException;
import io.browsercloud.application.ApplicationBusinessRecoveryService.SessionApplicationBindingNotFoundException;
import io.browsercloud.application.BreakGlassApplicationService.BreakGlassNotFoundException;
import io.browsercloud.application.BreakGlassApplicationService.BreakGlassRejectedException;
import io.browsercloud.application.BrowserCapacityApplicationService.BrowserCapacityUnavailableException;
import io.browsercloud.application.BrowserCapacityApplicationService.BrowserNodeNotFoundException;
import io.browsercloud.application.BrowserCapacityApplicationService.BrowserPlacementNotFoundException;
import io.browsercloud.application.BrowserCapacityApplicationService.ExtensionProfileRejectedException;
import io.browsercloud.application.CoordinatorCommandRoutingService.RoutedCoordinatorCommandException;
import io.browsercloud.application.EnterpriseOperationsApplicationService.EnterpriseResourceNotFoundException;
import io.browsercloud.application.EnterpriseOperationsApplicationService.GovernanceRejectedException;
import io.browsercloud.application.EnterpriseOperationsApplicationService.MediaQuotaRejectedException;
import io.browsercloud.application.EnvironmentImportApplicationService.EnvironmentImportNotFoundException;
import io.browsercloud.application.EnvironmentImportApplicationService.EnvironmentImportRejectedException;
import io.browsercloud.application.EnvironmentSavedViewApplicationService.EnvironmentSavedViewNotFoundException;
import io.browsercloud.application.EnvironmentSavedViewApplicationService.EnvironmentSavedViewRejectedException;
import io.browsercloud.application.KeyRotationApplicationService.KeyRotationNotFoundException;
import io.browsercloud.application.KeyRotationApplicationService.KeyRotationRejectedException;
import io.browsercloud.application.ProfileApplicationService.ProfileAlreadyExistsException;
import io.browsercloud.application.ProfileApplicationService.ProfileNotFoundException;
import io.browsercloud.application.ProfileImportApplicationService.ProfileImportRejectedException;
import io.browsercloud.application.ProfileImportApplicationService.ProfileImportUnavailableException;
import io.browsercloud.application.ProfileImportJobStore.ProfileImportConflictException;
import io.browsercloud.application.ProfileImportJobStore.ProfileImportNotFoundException;
import io.browsercloud.application.RuntimeBuildPolicy.RuntimeBuildRejectedException;
import io.browsercloud.application.RuntimeReleaseApplicationService.RuntimeReleaseNotFoundException;
import io.browsercloud.application.RuntimeReleaseApplicationService.RuntimeReleaseRejectedException;
import io.browsercloud.application.RuntimeValidationQueueApplicationService.RuntimeValidationJobNotFoundException;
import io.browsercloud.application.RuntimeValidationQueueApplicationService.RuntimeValidationJobRejectedException;
import io.browsercloud.application.SafePointApplicationService.SafePointNotFoundException;
import io.browsercloud.application.SecureDebugApplicationService.SecureDebugNotFoundException;
import io.browsercloud.application.SecureDebugApplicationService.SecureDebugRejectedException;
import io.browsercloud.application.SessionApplicationService.CapacityUnavailableException;
import io.browsercloud.application.SessionApplicationService.HumanTakeoverDisabledException;
import io.browsercloud.application.SessionEvidenceAccessNodeGateway.EvidenceAccessNodeRejectedException;
import io.browsercloud.application.SessionEvidenceAccessNodeGateway.EvidenceAccessNodeUnavailableException;
import io.browsercloud.application.SessionEvidenceGovernanceService.EvidenceGovernanceNotFoundException;
import io.browsercloud.application.SessionEvidenceGovernanceService.EvidenceGovernanceRejectedException;
import io.browsercloud.application.SessionMigrationApplicationService.MigrationRejectedException;
import io.browsercloud.application.SessionResourceApplicationService.ResourcePolicyActionRejectedException;
import io.browsercloud.application.SessionResourceApplicationService.ResourcePolicyNotFoundException;
import io.browsercloud.application.SessionResourceApplicationService.ResourcePolicyPermissionException;
import io.browsercloud.application.SessionResourceApplicationService.ResourceTelemetryRejectedException;
import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamCapacityException;
import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamConnectionException;
import io.browsercloud.application.SessionSafetyLeaseApplicationService.SafetyLeaseNotFoundException;
import io.browsercloud.application.SessionSafetyLeaseApplicationService.SafetyLeaseRejectedException;
import io.browsercloud.application.StateGatewayApplicationService.InvalidStateResyncRequestException;
import io.browsercloud.application.StaticProxyApplicationService.ProxyBindingNotFoundException;
import io.browsercloud.application.StaticProxyApplicationService.ProxyBindingRejectedException;
import io.browsercloud.application.StaticProxyApplicationService.ProxyUnavailableException;
import io.browsercloud.application.TenantRouteApplicationService.TenantRouteRejectedException;
import io.browsercloud.application.WorkspaceBatchOperationApplicationService.WorkspaceBatchOperationNotFoundException;
import io.browsercloud.application.WorkspaceBatchOperationApplicationService.WorkspaceBatchOperationRejectedException;
import io.browsercloud.application.WorkspaceGroupApplicationService.WorkspaceGroupNotFoundException;
import io.browsercloud.application.WorkspaceGroupApplicationService.WorkspaceGroupRejectedException;
import io.browsercloud.application.WorkspaceMetadataBatchOperationApplicationService.WorkspaceMetadataBatchOperationNotFoundException;
import io.browsercloud.application.WorkspaceMetadataBatchOperationApplicationService.WorkspaceMetadataBatchOperationRejectedException;
import io.browsercloud.application.WorkspaceTagApplicationService.WorkspaceTagNotFoundException;
import io.browsercloud.application.WorkspaceTagApplicationService.WorkspaceTagRejectedException;
import io.browsercloud.coordinator.SessionCoordinator.CoordinatorShardNotLocalException;
import io.browsercloud.coordinator.exceptions.ActiveOperationExistsException;
import io.browsercloud.coordinator.exceptions.CoordinatorNotOwnerException;
import io.browsercloud.coordinator.exceptions.IdempotencyConflictException;
import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.coordinator.exceptions.StaleContextEpochException;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.AgentTaskSummaryQueryRepository.InvalidAgentTaskSummaryCursorException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 将领域异常转换为正式契约中的 Error Envelope，避免向客户端泄露堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(EnvironmentImportNotFoundException.class)
  ResponseEntity<ApiError> environmentImportNotFound(
      EnvironmentImportNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "ENVIRONMENT_IMPORT_NOT_FOUND",
        "Environment Import was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(EnvironmentImportRejectedException.class)
  ResponseEntity<ApiError> environmentImportRejected(
      EnvironmentImportRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "ENVIRONMENT_IMPORT_REJECTED",
        "Environment Import cannot be committed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(EnvironmentSavedViewNotFoundException.class)
  ResponseEntity<ApiError> environmentSavedViewNotFound(
      EnvironmentSavedViewNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "ENVIRONMENT_SAVED_VIEW_NOT_FOUND",
        "Environment Saved View was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(EnvironmentSavedViewRejectedException.class)
  ResponseEntity<ApiError> environmentSavedViewRejected(
      EnvironmentSavedViewRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "ENVIRONMENT_SAVED_VIEW_REJECTED",
        "Environment Saved View cannot be changed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(TenantRouteRejectedException.class)
  ResponseEntity<ApiError> tenantRouteRejected(
      TenantRouteRejectedException exception, HttpServletRequest request) {
    var status =
        "TENANT_ROUTE_MIGRATION_NOT_FOUND".equals(exception.getMessage())
            ? HttpStatus.NOT_FOUND
            : HttpStatus.CONFLICT;
    return response(
        status,
        "TENANT_ROUTE_REJECTED",
        "Tenant Coordinator route operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(RoutedCoordinatorCommandException.class)
  ResponseEntity<ApiError> routedCoordinatorCommand(
      RoutedCoordinatorCommandException exception, HttpServletRequest request) {
    var details = new java.util.LinkedHashMap<String, Object>();
    details.put("reason", exception.getMessage());
    if (exception.commandId() != null) {
      details.put("commandId", exception.commandId());
    }
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COORDINATOR_COMMAND_UNAVAILABLE",
        "The Session command could not be committed by its Coordinator shard",
        details,
        request);
  }

  @ExceptionHandler(CoordinatorShardNotLocalException.class)
  ResponseEntity<ApiError> coordinatorShardNotLocal(
      CoordinatorShardNotLocalException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COORDINATOR_SHARD_NOT_LOCAL",
        "The Session command reached a non-owning Coordinator worker",
        Map.of(),
        request);
  }

  @ExceptionHandler(WorkspaceGroupNotFoundException.class)
  ResponseEntity<ApiError> workspaceGroupNotFound(
      WorkspaceGroupNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_GROUP_NOT_FOUND",
        "Workspace Group not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(WorkspaceBatchOperationNotFoundException.class)
  ResponseEntity<ApiError> workspaceBatchOperationNotFound(
      WorkspaceBatchOperationNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_BATCH_OPERATION_NOT_FOUND",
        "Workspace Batch Operation not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(WorkspaceBatchOperationRejectedException.class)
  ResponseEntity<ApiError> workspaceBatchOperationRejected(
      WorkspaceBatchOperationRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "WORKSPACE_BATCH_OPERATION_REJECTED",
        "Workspace Batch Operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(WorkspaceMetadataBatchOperationNotFoundException.class)
  ResponseEntity<ApiError> workspaceMetadataBatchOperationNotFound(
      WorkspaceMetadataBatchOperationNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_METADATA_BATCH_OPERATION_NOT_FOUND",
        "Workspace Metadata Batch Operation not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(WorkspaceMetadataBatchOperationRejectedException.class)
  ResponseEntity<ApiError> workspaceMetadataBatchOperationRejected(
      WorkspaceMetadataBatchOperationRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "WORKSPACE_METADATA_BATCH_OPERATION_REJECTED",
        "Workspace Metadata Batch Operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(WorkspaceGroupRejectedException.class)
  ResponseEntity<ApiError> workspaceGroupRejected(
      WorkspaceGroupRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "WORKSPACE_GROUP_REJECTED",
        "Workspace Group operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(WorkspaceTagNotFoundException.class)
  ResponseEntity<ApiError> workspaceTagNotFound(
      WorkspaceTagNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_TAG_NOT_FOUND",
        "Workspace Tag not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(WorkspaceTagRejectedException.class)
  ResponseEntity<ApiError> workspaceTagRejected(
      WorkspaceTagRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "WORKSPACE_TAG_REJECTED",
        "Workspace Tag operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(GovernanceRejectedException.class)
  ResponseEntity<ApiError> governanceRejected(
      GovernanceRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "ENTERPRISE_GOVERNANCE_REJECTED",
        "Enterprise governance policy rejected the operation",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(MediaQuotaRejectedException.class)
  ResponseEntity<ApiError> mediaQuotaRejected(
      MediaQuotaRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "MEDIA_QUOTA_REJECTED",
        "Media capacity admission was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(SessionNotFoundException.class)
  ResponseEntity<ApiError> notFound(
      SessionNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found", Map.of(), request);
  }

  @ExceptionHandler(EvidenceGovernanceNotFoundException.class)
  ResponseEntity<ApiError> evidenceGovernanceNotFound(
      EvidenceGovernanceNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SESSION_EVIDENCE_NOT_FOUND",
        "Session evidence resource was not found",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(EvidenceGovernanceRejectedException.class)
  ResponseEntity<ApiError> evidenceGovernanceRejected(
      EvidenceGovernanceRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SESSION_EVIDENCE_REQUEST_REJECTED",
        "Session evidence request cannot be completed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(EvidenceAccessNodeRejectedException.class)
  ResponseEntity<ApiError> evidenceAccessNodeRejected(
      EvidenceAccessNodeRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_GATEWAY,
        "EVIDENCE_ACCESS_NODE_REJECTED",
        "Browser Node rejected the evidence access request",
        Map.of(),
        request);
  }

  @ExceptionHandler(EvidenceAccessNodeUnavailableException.class)
  ResponseEntity<ApiError> evidenceAccessNodeUnavailable(
      EvidenceAccessNodeUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EVIDENCE_ACCESS_NODE_UNAVAILABLE",
        "Browser Node evidence access service is unavailable",
        Map.of(),
        request);
  }

  @ExceptionHandler(HumanTakeoverDisabledException.class)
  ResponseEntity<ApiError> humanTakeoverDisabled(
      HumanTakeoverDisabledException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "HUMAN_TAKEOVER_DISABLED",
        "HumanTakeover is disabled for this Session",
        Map.of(),
        request);
  }

  @ExceptionHandler(ResourcePolicyNotFoundException.class)
  ResponseEntity<ApiError> resourcePolicyNotFound(
      ResourcePolicyNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "RESOURCE_POLICY_NOT_FOUND",
        "Session resource policy not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(ResourcePolicyActionRejectedException.class)
  ResponseEntity<ApiError> resourcePolicyActionRejected(
      ResourcePolicyActionRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RESOURCE_POLICY_ACTION_REJECTED",
        "Session resource policy action was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(SafePointNotFoundException.class)
  ResponseEntity<ApiError> safePointNotFound(
      SafePointNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SAFE_POINT_NOT_FOUND",
        "Session safe-point state not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(SafetyLeaseNotFoundException.class)
  ResponseEntity<ApiError> safetyLeaseNotFound(
      SafetyLeaseNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SAFETY_LEASE_NOT_FOUND",
        "Session safety lease not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(SafetyLeaseRejectedException.class)
  ResponseEntity<ApiError> safetyLeaseRejected(
      SafetyLeaseRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SAFETY_LEASE_REJECTED",
        "Session safety lease cannot be acquired or renewed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ResourcePolicyPermissionException.class)
  ResponseEntity<ApiError> resourcePolicyPermission(
      ResourcePolicyPermissionException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN,
        "STRICT_RESOURCE_POLICY_REQUIRES_PLATFORM_ADMIN",
        "Strict termination policy requires Platform Admin",
        Map.of(),
        request);
  }

  @ExceptionHandler(ResourceTelemetryRejectedException.class)
  ResponseEntity<ApiError> resourceTelemetryRejected(
      ResourceTelemetryRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RESOURCE_TELEMETRY_REJECTED",
        "Resource sample does not match the active Browser placement",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ResourceStreamCapacityException.class)
  ResponseEntity<ApiError> resourceStreamCapacity(
      ResourceStreamCapacityException exception, HttpServletRequest request) {
    return response(
        HttpStatus.TOO_MANY_REQUESTS,
        "RESOURCE_STREAM_CAPACITY_EXCEEDED",
        "Too many live resource stream subscribers",
        Map.of(),
        request);
  }

  @ExceptionHandler(ResourceStreamConnectionException.class)
  ResponseEntity<ApiError> resourceStreamConnection(
      ResourceStreamConnectionException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RESOURCE_STREAM_UNAVAILABLE",
        "The resource event stream is temporarily unavailable",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProfileNotFoundException.class)
  ResponseEntity<ApiError> profileNotFound(
      ProfileNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profile not found", Map.of(), request);
  }

  @ExceptionHandler(AgentTaskNotFoundException.class)
  ResponseEntity<ApiError> agentTaskNotFound(
      AgentTaskNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found", Map.of(), request);
  }

  @ExceptionHandler(BreakGlassNotFoundException.class)
  ResponseEntity<ApiError> breakGlassNotFound(
      BreakGlassNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "BREAK_GLASS_NOT_FOUND",
        "Break-glass request not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(BreakGlassRejectedException.class)
  ResponseEntity<ApiError> breakGlassRejected(
      BreakGlassRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "BREAK_GLASS_REJECTED",
        "Break-glass transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(SecureDebugNotFoundException.class)
  ResponseEntity<ApiError> secureDebugNotFound(
      SecureDebugNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SECURE_DEBUG_NOT_FOUND",
        "Secure Debug session was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(SecureDebugRejectedException.class)
  ResponseEntity<ApiError> secureDebugRejected(
      SecureDebugRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SECURE_DEBUG_REJECTED",
        "Secure Debug access was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(KeyRotationNotFoundException.class)
  ResponseEntity<ApiError> keyRotationNotFound(
      KeyRotationNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "KEY_ROTATION_NOT_FOUND",
        "Key rotation request was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(KeyRotationRejectedException.class)
  ResponseEntity<ApiError> keyRotationRejected(
      KeyRotationRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "KEY_ROTATION_REJECTED",
        "Key rotation transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(InvalidAgentTaskException.class)
  ResponseEntity<ApiError> invalidAgentTask(
      InvalidAgentTaskException exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST,
        "AGENT_TASK_INVALID",
        "Agent task validation failed",
        Map.of(),
        request);
  }

  @ExceptionHandler(InvalidAgentTaskSummaryCursorException.class)
  ResponseEntity<ApiError> invalidAgentTaskSummaryCursor(
      InvalidAgentTaskSummaryCursorException exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST,
        "AGENT_TASK_CURSOR_INVALID",
        "Agent task pagination cursor is invalid",
        Map.of(),
        request);
  }

  @ExceptionHandler(AgentExecutionRejectedException.class)
  ResponseEntity<ApiError> agentExecutionRejected(
      AgentExecutionRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "AGENT_EXECUTION_REJECTED",
        "Agent task cannot be executed in its current state",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(HumanGovernanceException.class)
  ResponseEntity<ApiError> humanGovernance(
      HumanGovernanceException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "HUMAN_GOVERNANCE_REJECTED",
        "Human confirmation or takeover handoff cannot be completed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ProfileAlreadyExistsException.class)
  ResponseEntity<ApiError> profileConflict(
      ProfileAlreadyExistsException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT, "PROFILE_ALREADY_EXISTS", "Profile already exists", Map.of(), request);
  }

  @ExceptionHandler(ProfileImportNotFoundException.class)
  ResponseEntity<ApiError> profileImportNotFound(
      ProfileImportNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "PROFILE_IMPORT_NOT_FOUND",
        "Profile Import was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProfileImportConflictException.class)
  ResponseEntity<ApiError> profileImportConflict(
      ProfileImportConflictException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "PROFILE_IMPORT_CONFLICT",
        "Profile Import conflicts with current state",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ProfileImportRejectedException.class)
  ResponseEntity<ApiError> profileImportRejected(
      ProfileImportRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "PROFILE_IMPORT_REJECTED",
        "Profile archive could not be validated or committed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> multipartPayloadTooLarge(
      MaxUploadSizeExceededException exception, HttpServletRequest request) {
    return response(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "UPLOAD_SIZE_EXCEEDED",
        "Uploaded archive exceeds the configured request limit",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProfileImportUnavailableException.class)
  ResponseEntity<ApiError> profileImportUnavailable(
      ProfileImportUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PROFILE_IMPORT_UNAVAILABLE",
        "No verified Profile Import data plane is currently available",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ProxyUnavailableException.class)
  ResponseEntity<ApiError> proxyUnavailable(
      ProxyUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PROXY_UNAVAILABLE",
        "A verified network exit is unavailable",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProxyBindingNotFoundException.class)
  ResponseEntity<ApiError> proxyBindingNotFound(
      ProxyBindingNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "PROXY_BINDING_NOT_FOUND",
        "Proxy Binding not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProxyBindingRejectedException.class)
  ResponseEntity<ApiError> proxyBindingRejected(
      ProxyBindingRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "PROXY_BINDING_REJECTED",
        "Proxy Binding operation was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(MigrationRejectedException.class)
  ResponseEntity<ApiError> migrationRejected(
      MigrationRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SESSION_WORKFLOW_REJECTED",
        "Session migration or proxy rebind was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(RuntimeBuildRejectedException.class)
  ResponseEntity<ApiError> runtimeBuildRejected(
      RuntimeBuildRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RUNTIME_BUILD_REJECTED",
        "The configured Runtime Build is not approved for release",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(RuntimeReleaseNotFoundException.class)
  ResponseEntity<ApiError> runtimeReleaseNotFound(
      RuntimeReleaseNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "RUNTIME_RELEASE_NOT_FOUND",
        "Runtime release request was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RuntimeReleaseRejectedException.class)
  ResponseEntity<ApiError> runtimeReleaseRejected(
      RuntimeReleaseRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RUNTIME_RELEASE_REJECTED",
        "Runtime release transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(CapacityUnavailableException.class)
  ResponseEntity<ApiError> capacityUnavailable(
      CapacityUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CAPACITY_UNAVAILABLE",
        "Admission is temporarily closed by online capacity feedback",
        Map.of(),
        request);
  }

  @ExceptionHandler(BrowserCapacityUnavailableException.class)
  ResponseEntity<ApiError> browserCapacityUnavailable(
      BrowserCapacityUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "BROWSER_CAPACITY_UNAVAILABLE",
        "No Browser Node can safely admit this resource demand",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler({BrowserNodeNotFoundException.class, BrowserPlacementNotFoundException.class})
  ResponseEntity<ApiError> browserCapacityResourceNotFound(
      RuntimeException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "BROWSER_CAPACITY_RESOURCE_NOT_FOUND",
        "Browser capacity resource was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(EnterpriseResourceNotFoundException.class)
  ResponseEntity<ApiError> enterpriseResourceNotFound(
      EnterpriseResourceNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "ENTERPRISE_RESOURCE_NOT_FOUND",
        "Enterprise operations resource was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RuntimeValidationJobNotFoundException.class)
  ResponseEntity<ApiError> runtimeValidationJobNotFound(
      RuntimeValidationJobNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "RUNTIME_VALIDATION_JOB_NOT_FOUND",
        "Runtime Validation job was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RuntimeValidationJobRejectedException.class)
  ResponseEntity<ApiError> runtimeValidationJobRejected(
      RuntimeValidationJobRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RUNTIME_VALIDATION_JOB_REJECTED",
        "Runtime Validation Worker claim or transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ExtensionProfileRejectedException.class)
  ResponseEntity<ApiError> extensionProfileRejected(
      ExtensionProfileRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "EXTENSION_PROFILE_REJECTED",
        "Extension profile cannot be used for placement",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler({
    RecoveryContractNotFoundException.class,
    RecoveryContractApprovalNotFoundException.class,
    BusinessRecoveryValidationNotFoundException.class
  })
  ResponseEntity<ApiError> businessRecoveryNotFound(
      RuntimeException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "BUSINESS_RECOVERY_NOT_FOUND",
        "Business Recovery resource was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RecoveryContractVersionConflictException.class)
  ResponseEntity<ApiError> recoveryContractVersionConflict(
      RecoveryContractVersionConflictException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RECOVERY_CONTRACT_VERSION_CONFLICT",
        "Application Recovery Contract version changed",
        Map.of(),
        request);
  }

  @ExceptionHandler(RecoveryContractRejectedException.class)
  ResponseEntity<ApiError> recoveryContractRejected(
      RecoveryContractRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "RECOVERY_CONTRACT_REJECTED",
        "Application Recovery Contract is invalid",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(RecoveryContractApprovalRequiredException.class)
  ResponseEntity<ApiError> recoveryContractApprovalRequired(
      RecoveryContractApprovalRequiredException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RECOVERY_CONTRACT_APPROVAL_REQUIRED",
        "The exact Application Recovery Contract version is not approved",
        Map.of(),
        request);
  }

  @ExceptionHandler(SessionApplicationBindingNotFoundException.class)
  ResponseEntity<ApiError> sessionApplicationBindingNotFound(
      SessionApplicationBindingNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SESSION_APPLICATION_BINDING_NOT_FOUND",
        "Session Application Recovery binding was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RecoveryContractApprovalRejectedException.class)
  ResponseEntity<ApiError> recoveryContractApprovalRejected(
      RecoveryContractApprovalRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RECOVERY_CONTRACT_APPROVAL_REJECTED",
        "Application Recovery Contract approval cannot proceed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(BusinessRecoveryStateUnavailableException.class)
  ResponseEntity<ApiError> businessRecoveryStateUnavailable(
      BusinessRecoveryStateUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "BUSINESS_RECOVERY_STATE_UNAVAILABLE",
        "Current authoritative Browser State is not available",
        Map.of(),
        request);
  }

  @ExceptionHandler(ProviderEvidenceRejectedException.class)
  ResponseEntity<ApiError> providerEvidenceRejected(
      ProviderEvidenceRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "BUSINESS_RECOVERY_PROVIDER_EVIDENCE_REJECTED",
        "Provider evidence cannot be accepted for the current Session state",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(TenantAccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(
      TenantAccessDeniedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN, "CAPABILITY_DENIED", "Session is not accessible", Map.of(), request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> roleForbidden(
      AccessDeniedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN,
        "ROLE_FORBIDDEN",
        "The authenticated identity lacks the required role",
        Map.of(),
        request);
  }

  @ExceptionHandler(ActiveOperationExistsException.class)
  ResponseEntity<ApiError> activeOperation(
      ActiveOperationExistsException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "OPERATION_ACTIVE",
        "An active operation already exists",
        Map.of(),
        request);
  }

  @ExceptionHandler(CoordinatorNotOwnerException.class)
  ResponseEntity<ApiError> coordinatorNotOwner(
      CoordinatorNotOwnerException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COORDINATOR_NOT_OWNER",
        "The Session Coordinator is owned by another instance",
        Map.of(),
        request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ApiError> idempotencyConflict(
      IdempotencyConflictException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "Idempotency key was already used for a different request",
        Map.of(),
        request);
  }

  @ExceptionHandler(InvalidSessionStateException.class)
  ResponseEntity<ApiError> invalidState(
      InvalidSessionStateException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SESSION_INVALID_STATE",
        "Session state does not allow this operation",
        Map.of("state", exception.state().name()),
        request);
  }

  @ExceptionHandler(InvalidStateResyncRequestException.class)
  ResponseEntity<ApiError> invalidStateResync(
      InvalidStateResyncRequestException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "STATE_RESYNC_REJECTED",
        "State Resync request is not valid for the current Session",
        Map.of(),
        request);
  }

  @ExceptionHandler(StaleContextEpochException.class)
  ResponseEntity<ApiError> staleContext(
      StaleContextEpochException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "CONTEXT_EPOCH_MISMATCH",
        "Session context changed; retry with fresh state",
        Map.of(),
        request);
  }

  @ExceptionHandler(StaleOperationException.class)
  ResponseEntity<ApiError> staleOperation(
      StaleOperationException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "OPERATION_STATE_MISMATCH",
        "Operation state changed; retry with fresh state",
        Map.of(),
        request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Request validation failed", Map.of(), request);
  }

  @ExceptionHandler(AsyncRequestTimeoutException.class)
  void asyncRequestCompleted(AsyncRequestTimeoutException exception) {
    // SSE clients routinely disconnect or rotate long-lived connections after headers commit.
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> internal(Exception exception, HttpServletRequest request) {
    if (isDatabaseTransactionRetry(exception)) {
      log.warn("Authoritative database transaction must be retried after a transient conflict");
      return response(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DATABASE_TRANSACTION_RETRY",
          "The authoritative database transaction encountered a transient conflict",
          Map.of("retryable", true),
          request);
    }
    if (isDatabaseUnavailable(exception)) {
      log.warn("Authoritative database is temporarily unavailable");
      return response(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DATABASE_UNAVAILABLE",
          "The authoritative database is temporarily unavailable",
          Map.of(),
          request);
    }
    log.error("Unhandled request failure", exception);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed",
        Map.of(),
        request);
  }

  private boolean isDatabaseTransactionRetry(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (current instanceof SQLException sqlException
          && ("40P01".equals(sqlException.getSQLState())
              || "40001".equals(sqlException.getSQLState()))) {
        return true;
      }
      if (current.getCause() == current) {
        break;
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean isDatabaseUnavailable(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 12; depth++) {
      if (current instanceof DataAccessResourceFailureException
          || current instanceof JDBCConnectionException
          || current instanceof SQLException sqlException
              && sqlException.getSQLState() != null
              && sqlException.getSQLState().startsWith("08")) {
        return true;
      }
      if (current.getCause() == current) {
        break;
      }
      current = current.getCause();
    }
    return false;
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status,
      String code,
      String message,
      Map<String, Object> details,
      HttpServletRequest request) {
    String requestId = (String) request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE);
    return ResponseEntity.status(status)
        .body(new ApiError(code, message, details, requestId, Instant.now()));
  }
}
