// Code generated from session-api.yaml; DO NOT EDIT.
package io.browsercloud.sdk.generated;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BrowserCloudGeneratedClient {
  public static final String GENERATOR = "browsercloud-multilang-generator@1";
  public interface Transport { Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException; }
  public record Response(int status, Map<String, String> headers, String body) {}
  public record Request(Map<String, String> path, Map<String, List<String>> query, Map<String, String> headers, String jsonBody) {
    public Request { path = path == null ? Map.of() : Map.copyOf(path); query = query == null ? Map.of() : Map.copyOf(query); headers = headers == null ? Map.of() : Map.copyOf(headers); }
    public static Request empty() { return new Request(Map.of(), Map.of(), Map.of(), null); }
  }
  public record Operation(String operationId, String method, String path, List<String> pathParameters, List<String> queryParameters, List<String> headerParameters, String requestSchema, boolean requestRequired, String responseSchema) {}
  public static final class ApiException extends RuntimeException {
    private final int status; private final String code; private final String requestId;
    public ApiException(int status, String code, String message, String requestId) { super(message); this.status = status; this.code = code; this.requestId = requestId; }
    public int status() { return status; } public String code() { return code; } public String requestId() { return requestId; }
  }
  public static final Map<String, Operation> OPERATIONS = List.of(
    operation("getWorkspaceOverview", "GET", "/api/v1/workspace-overview", List.of(), List.of(), List.of(), "", false, "WorkspaceOverview"),
    operation("streamWorkspaceOverviewChanges", "GET", "/api/v1/workspace-overview/event-stream", List.of(), List.of(), List.of("Last-Event-ID"), "", false, "string"),
    operation("getTenantCoordinatorRoute", "GET", "/api/v1/coordinator/tenant-route", List.of(), List.of(), List.of(), "", false, "TenantRoute"),
    operation("getLatestTenantCoordinatorRouteMigration", "GET", "/api/v1/coordinator/tenant-route/migration", List.of(), List.of(), List.of(), "", false, "TenantRouteMigration"),
    operation("requestTenantCoordinatorRouteMigration", "POST", "/api/v1/coordinator/tenant-route/migrations", List.of(), List.of(), List.of("Idempotency-Key"), "RequestTenantRouteMigration", true, "TenantRouteMigration"),
    operation("globalSearch", "GET", "/api/v1/search", List.of(), List.of("limit", "q", "types"), List.of(), "", false, "GlobalSearchResponse"),
    operation("listWorkspaceNotifications", "GET", "/api/v1/notifications", List.of(), List.of("beforeSequence", "limit"), List.of(), "", false, "WorkspaceNotificationListResponse"),
    operation("streamWorkspaceNotificationChanges", "GET", "/api/v1/notifications/event-stream", List.of(), List.of(), List.of("Last-Event-ID"), "", false, "string"),
    operation("updateWorkspaceNotificationReadCursor", "PATCH", "/api/v1/notifications/read-cursor", List.of(), List.of(), List.of(), "UpdateNotificationReadCursorRequest", true, "WorkspaceNotificationReadState"),
    operation("getUserPreferences", "GET", "/api/v1/user-preferences", List.of(), List.of(), List.of(), "", false, "UserPreferences"),
    operation("updateUserPreferences", "PUT", "/api/v1/user-preferences", List.of(), List.of(), List.of(), "UpdateUserPreferencesRequest", true, "UserPreferences"),
    operation("listSessions", "GET", "/api/v1/sessions", List.of(), List.of("groupId", "limit", "offset", "q", "state", "tagId", "tagMatch"), List.of("X-Tenant-Id"), "", false, "SessionListResponse"),
    operation("createSession", "POST", "/api/v1/sessions", List.of(), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "CreateSessionRequest", true, "CreateSessionResponse"),
    operation("getSession", "GET", "/api/v1/sessions/{sessionId}", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionView"),
    operation("getBrowserState", "GET", "/api/v1/sessions/{sessionId}/state", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "BrowserState"),
    operation("getSessionResources", "GET", "/api/v1/sessions/{sessionId}/resources", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionResource"),
    operation("listSessionResourceEvents", "GET", "/api/v1/sessions/{sessionId}/resource-events", List.of("sessionId"), List.of("limit", "offset"), List.of("X-Tenant-Id"), "", false, "ResourceEventList"),
    operation("listSessionEvidence", "GET", "/api/v1/sessions/{sessionId}/evidence", List.of("sessionId"), List.of("limit", "offset"), List.of("X-Tenant-Id"), "", false, "EvidenceList"),
    operation("captureSessionEvidence", "POST", "/api/v1/sessions/{sessionId}/evidence:capture", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "CaptureEvidenceRequest", true, "EvidenceCapture"),
    operation("getSessionEvidenceCapture", "GET", "/api/v1/sessions/{sessionId}/evidence-captures/{captureId}", List.of("captureId", "sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "EvidenceCapture"),
    operation("createSessionEvidenceAccessGrant", "POST", "/api/v1/sessions/{sessionId}/evidence/{evidenceId}/access-grants", List.of("evidenceId", "sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "CreateEvidenceAccessGrantRequest", true, "EvidenceAccessGrant"),
    operation("redeemSessionEvidenceAccessGrant", "POST", "/api/v1/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem", List.of("grantId", "sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "RedeemEvidenceAccessResponse"),
    operation("streamSessionResourceChanges", "GET", "/api/v1/sessions/{sessionId}/resource-stream", List.of("sessionId"), List.of(), List.of("Last-Event-ID", "X-Tenant-Id"), "", false, "string"),
    operation("streamSessionChanges", "GET", "/api/v1/sessions/{sessionId}/event-stream", List.of("sessionId"), List.of(), List.of("Last-Event-ID", "X-Tenant-Id"), "", false, "string"),
    operation("getSessionSafePoint", "GET", "/api/v1/sessions/{sessionId}/safe-point", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionSafePoint"),
    operation("listSessionSafetyLeases", "GET", "/api/v1/sessions/{sessionId}/safety-leases", List.of("sessionId"), List.of("limit"), List.of("X-Tenant-Id"), "", false, "SafetyLeaseList"),
    operation("acquireSessionSafetyLease", "POST", "/api/v1/sessions/{sessionId}/safety-leases", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "CreateSafetyLeaseRequest", true, "SafetyLease"),
    operation("renewSessionSafetyLease", "PUT", "/api/v1/sessions/{sessionId}/safety-leases/{leaseId}", List.of("leaseId", "sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "RenewSafetyLeaseRequest", true, "SafetyLease"),
    operation("releaseSessionSafetyLease", "POST", "/api/v1/sessions/{sessionId}/safety-leases/{leaseId}:release", List.of("leaseId", "sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "", false, "SafetyLease"),
    operation("getLatestSessionMigration", "GET", "/api/v1/sessions/{sessionId}/migration", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionMigration"),
    operation("rebindSessionProxy", "POST", "/api/v1/sessions/{sessionId}/proxy-binding:rebind", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "ProxyRebindRequest", true, "ProxyRebindOperation"),
    operation("getLatestSessionProxyRebind", "GET", "/api/v1/sessions/{sessionId}/proxy-rebind", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "ProxyRebind"),
    operation("getBusinessRecoveryValidation", "GET", "/api/v1/sessions/{sessionId}/business-recovery", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "BusinessRecoveryValidation"),
    operation("validateBusinessRecovery", "POST", "/api/v1/sessions/{sessionId}/business-recovery:validate", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "", false, "BusinessRecoveryValidation"),
    operation("listBusinessRecoveryProviderEvidence", "GET", "/api/v1/sessions/{sessionId}/business-recovery/provider-evidence", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "ProviderEvidenceListResponse"),
    operation("submitBusinessRecoveryProviderEvidence", "POST", "/api/v1/sessions/{sessionId}/business-recovery/provider-evidence", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "SubmitProviderEvidenceRequest", true, "ProviderEvidence"),
    operation("getSessionApplicationBinding", "GET", "/api/v1/sessions/{sessionId}/application-binding", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionApplicationBinding"),
    operation("rebindSessionApplicationContract", "POST", "/api/v1/sessions/{sessionId}/application-binding:rebind", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "RebindSessionApplicationRequest", true, "SessionApplicationRebind"),
    operation("listApplicationRecoveryContracts", "GET", "/api/v1/applications/recovery-contracts", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "RecoveryContractListResponse"),
    operation("getApplicationRecoveryContract", "GET", "/api/v1/applications/{applicationId}/recovery-contract", List.of("applicationId"), List.of(), List.of("X-Tenant-Id"), "", false, "RecoveryContract"),
    operation("upsertApplicationRecoveryContract", "PUT", "/api/v1/applications/{applicationId}/recovery-contract", List.of("applicationId"), List.of(), List.of("X-Tenant-Id"), "UpsertRecoveryContractRequest", true, "RecoveryContract"),
    operation("listApplicationRecoveryContractRevisions", "GET", "/api/v1/applications/{applicationId}/recovery-contract/revisions", List.of("applicationId"), List.of(), List.of("X-Tenant-Id"), "", false, "RecoveryContractRevisionListResponse"),
    operation("diffApplicationRecoveryContractRevisions", "GET", "/api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff", List.of("applicationId", "version"), List.of("compareToVersion"), List.of("X-Tenant-Id"), "", false, "RecoveryContractDiff"),
    operation("restoreApplicationRecoveryContractRevision", "POST", "/api/v1/applications/{applicationId}/recovery-contract:restore", List.of("applicationId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "RestoreRecoveryContractRevisionRequest", true, "RecoveryContract"),
    operation("requestApplicationRecoveryContractApproval", "POST", "/api/v1/applications/{applicationId}/recovery-contract:request-approval", List.of("applicationId"), List.of(), List.of("X-Tenant-Id"), "RequestRecoveryContractApprovalRequest", true, "RecoveryContractApproval"),
    operation("approveApplicationRecoveryContract", "POST", "/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve", List.of("applicationId", "approvalId"), List.of(), List.of("X-Tenant-Id"), "", false, "RecoveryContractApproval"),
    operation("rejectApplicationRecoveryContract", "POST", "/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject", List.of("applicationId", "approvalId"), List.of(), List.of("X-Tenant-Id"), "", false, "RecoveryContractApproval"),
    operation("updateSessionResourcePolicy", "PATCH", "/api/v1/sessions/{sessionId}/resource-policy", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "ResourcePolicyRequest", true, "ResourcePolicyOperation"),
    operation("startSession", "POST", "/api/v1/sessions/{sessionId}:start", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "OperationResponse"),
    operation("resyncBrowserState", "POST", "/api/v1/sessions/{sessionId}:resync-state", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "StateResyncRequest", true, "StateResyncResponse"),
    operation("terminateSession", "POST", "/api/v1/sessions/{sessionId}:terminate", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "OperationResponse"),
    operation("requestHumanTakeover", "POST", "/api/v1/sessions/{sessionId}:takeover", List.of("sessionId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "OperationResponse"),
    operation("releaseHumanTakeover", "POST", "/api/v1/sessions/{sessionId}:release-takeover", List.of("sessionId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "OperationResponse"),
    operation("createRemoteDesktopConnection", "POST", "/api/v1/sessions/{sessionId}:desktop-connection", List.of("sessionId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "RemoteDesktopConnection"),
    operation("listProfiles", "GET", "/api/v1/profiles", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "ProfileListResponse"),
    operation("createProfile", "POST", "/api/v1/profiles", List.of(), List.of(), List.of("X-Tenant-Id"), "CreateProfileRequest", true, "Profile"),
    operation("getProfile", "GET", "/api/v1/profiles/{profileId}", List.of("profileId"), List.of(), List.of("X-Tenant-Id"), "", false, "Profile"),
    operation("listProfileImports", "GET", "/api/v1/profile-imports", List.of(), List.of("limit"), List.of("X-Tenant-Id"), "", false, "ProfileImportListResponse"),
    operation("importProfileCheckpoint", "POST", "/api/v1/profile-imports", List.of(), List.of(), List.of("Idempotency-Key", "X-Actor-Id", "X-Tenant-Id"), "object", true, "ProfileImport"),
    operation("getProfileImport", "GET", "/api/v1/profile-imports/{importId}", List.of("importId"), List.of(), List.of("X-Tenant-Id"), "", false, "ProfileImport"),
    operation("getProxyOverview", "GET", "/api/v1/proxies", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "ProxyOverview"),
    operation("listProxyBindings", "GET", "/api/v1/proxy-bindings", List.of(), List.of(), List.of(), "", false, "ProxyBindingList"),
    operation("createProxyBinding", "POST", "/api/v1/proxy-bindings", List.of(), List.of(), List.of("Idempotency-Key"), "ProxyBindingRequest", true, "ProxyBinding"),
    operation("updateProxyBinding", "PUT", "/api/v1/proxy-bindings/{bindingProfileId}", List.of("bindingProfileId"), List.of(), List.of("Idempotency-Key"), "ProxyBindingRequest", true, "ProxyBinding"),
    operation("deleteProxyBinding", "DELETE", "/api/v1/proxy-bindings/{bindingProfileId}", List.of("bindingProfileId"), List.of(), List.of("Idempotency-Key"), "", false, ""),
    operation("createAgentTask", "POST", "/api/v1/sessions/{sessionId}/agent-tasks", List.of("sessionId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "CreateAgentTaskRequest", true, "AgentTask"),
    operation("listAgentTasks", "GET", "/api/v1/agent-tasks", List.of(), List.of("limit", "offset"), List.of("X-Tenant-Id"), "", false, "AgentTaskListResponse"),
    operation("listAgentTaskSummaries", "GET", "/api/v1/agent-task-summaries", List.of(), List.of("cursor", "limit"), List.of("X-Tenant-Id"), "", false, "AgentTaskSummaryListResponse"),
    operation("getAgentTask", "GET", "/api/v1/agent-tasks/{taskId}", List.of("taskId"), List.of(), List.of("X-Tenant-Id"), "", false, "AgentTask"),
    operation("executeAgentTask", "POST", "/api/v1/agent-tasks/{taskId}:execute", List.of("taskId"), List.of(), List.of("Idempotency-Key", "X-Tenant-Id"), "", false, "AgentTask"),
    operation("claimAgentExecutionJob", "POST", "/api/v1/agent-worker-jobs:claim", List.of(), List.of(), List.of(), "ClaimAgentExecutionJobRequest", true, "AgentExecutionJobClaim"),
    operation("startAgentExecutionJob", "POST", "/api/v1/agent-worker-jobs/{jobId}:start", List.of("jobId"), List.of(), List.of(), "AgentExecutionJobClaimRequest", true, "AgentExecutionJob"),
    operation("heartbeatAgentExecutionJob", "POST", "/api/v1/agent-worker-jobs/{jobId}:heartbeat", List.of("jobId"), List.of(), List.of(), "AgentExecutionJobClaimRequest", true, "AgentExecutionJob"),
    operation("driveAgentExecutionJob", "POST", "/api/v1/agent-worker-jobs/{jobId}:drive", List.of("jobId"), List.of(), List.of(), "AgentExecutionJobClaimRequest", true, "AgentExecutionJob"),
    operation("failAgentExecutionJob", "POST", "/api/v1/agent-worker-jobs/{jobId}:fail", List.of("jobId"), List.of(), List.of(), "FailAgentExecutionJobRequest", true, "AgentExecutionJob"),
    operation("claimAgentReviewJob", "POST", "/api/v1/agent-review-jobs:claim", List.of(), List.of(), List.of(), "ClaimAgentReviewJobRequest", true, "AgentReviewJobClaim"),
    operation("startAgentReviewJob", "POST", "/api/v1/agent-review-jobs/{jobId}:start", List.of("jobId"), List.of(), List.of(), "AgentReviewJobClaimRequest", true, "AgentReviewJob"),
    operation("heartbeatAgentReviewJob", "POST", "/api/v1/agent-review-jobs/{jobId}:heartbeat", List.of("jobId"), List.of(), List.of(), "AgentReviewJobClaimRequest", true, "AgentReviewJob"),
    operation("completeAgentReviewJob", "POST", "/api/v1/agent-review-jobs/{jobId}:complete", List.of("jobId"), List.of(), List.of(), "CompleteAgentReviewJobRequest", true, "AgentReviewJob"),
    operation("failAgentReviewJob", "POST", "/api/v1/agent-review-jobs/{jobId}:fail", List.of("jobId"), List.of(), List.of(), "FailAgentReviewJobRequest", true, "AgentReviewJob"),
    operation("approveAgentTask", "POST", "/api/v1/agent-tasks/{taskId}:approve", List.of("taskId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "AgentTask"),
    operation("rejectAgentTask", "POST", "/api/v1/agent-tasks/{taskId}:reject", List.of("taskId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "AgentTask"),
    operation("acceptAgentHandoff", "POST", "/api/v1/agent-tasks/{taskId}:accept-handoff", List.of("taskId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "AgentTask"),
    operation("rejectAgentHandoff", "POST", "/api/v1/agent-tasks/{taskId}:reject-handoff", List.of("taskId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "AgentTask"),
    operation("listAuditEvents", "GET", "/api/v1/audit-events", List.of(), List.of("eventType", "limit", "offset", "sessionId"), List.of("X-Tenant-Id"), "", false, "AuditEventListResponse"),
    operation("listRuntimeBuilds", "GET", "/api/v1/runtime-builds", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "RuntimeBuildListResponse"),
    operation("requestRuntimePromotion", "POST", "/api/v1/runtime-builds/{buildId}:promote", List.of("buildId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "CreateRuntimeReleaseRequest", true, "RuntimeReleaseRequest"),
    operation("requestRuntimeDisable", "POST", "/api/v1/runtime-builds/{buildId}:disable", List.of("buildId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "CreateRuntimeDisableRequest", true, "RuntimeReleaseRequest"),
    operation("listRuntimeReleaseRequests", "GET", "/api/v1/runtime-release-requests", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "RuntimeReleaseRequestListResponse"),
    operation("approveRuntimeRelease", "POST", "/api/v1/runtime-release-requests/{releaseId}:approve", List.of("releaseId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "RuntimeReleaseRequest"),
    operation("rejectRuntimeRelease", "POST", "/api/v1/runtime-release-requests/{releaseId}:reject", List.of("releaseId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "RuntimeReleaseRequest"),
    operation("listKeyRotationRequests", "GET", "/api/v1/key-rotation-requests", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "KeyRotationRequestListResponse"),
    operation("requestKeyRotation", "POST", "/api/v1/key-rotation-requests", List.of(), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "CreateKeyRotationRequest", true, "KeyRotationRequest"),
    operation("approveKeyRotation", "POST", "/api/v1/key-rotation-requests/{rotationId}:approve", List.of("rotationId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "KeyRotationRequest"),
    operation("completeKeyRotation", "POST", "/api/v1/key-rotation-requests/{rotationId}:complete", List.of("rotationId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "CompleteKeyRotationRequest", true, "KeyRotationRequest"),
    operation("revokeKeyRotation", "POST", "/api/v1/key-rotation-requests/{rotationId}:revoke", List.of("rotationId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "KeyRotationRequest"),
    operation("listBreakGlassRequests", "GET", "/api/v1/break-glass-requests", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "BreakGlassRequestListResponse"),
    operation("requestBreakGlass", "POST", "/api/v1/break-glass-requests", List.of(), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "CreateBreakGlassRequest", true, "BreakGlassRequest"),
    operation("approveBreakGlass", "POST", "/api/v1/break-glass-requests/{requestId}:approve", List.of("requestId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "BreakGlassRequest"),
    operation("rejectBreakGlass", "POST", "/api/v1/break-glass-requests/{requestId}:reject", List.of("requestId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "BreakGlassRequest"),
    operation("revokeBreakGlass", "POST", "/api/v1/break-glass-requests/{requestId}:revoke", List.of("requestId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "BreakGlassRequest"),
    operation("reviewBreakGlass", "POST", "/api/v1/break-glass-requests/{requestId}:review", List.of("requestId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "BreakGlassRequest"),
    operation("startSecureDebug", "POST", "/api/v1/break-glass-requests/{requestId}:start-secure-debug", List.of("requestId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "SecureDebugSession"),
    operation("listSecureDebugSessions", "GET", "/api/v1/secure-debug-sessions", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "SecureDebugSessionListResponse"),
    operation("readSecureDebugSnapshot", "GET", "/api/v1/secure-debug-sessions/{debugSessionId}/snapshot", List.of("debugSessionId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "SecureDebugSnapshot"),
    operation("endSecureDebug", "POST", "/api/v1/secure-debug-sessions/{debugSessionId}:end", List.of("debugSessionId"), List.of(), List.of("X-Actor-Id", "X-Tenant-Id"), "", false, "SecureDebugSession"),
    operation("listBrowserNodes", "GET", "/api/v1/browser-nodes", List.of(), List.of(), List.of(), "", false, "BrowserNodeListResponse"),
    operation("registerBrowserNode", "PUT", "/api/v1/browser-nodes/{nodeId}", List.of("nodeId"), List.of(), List.of(), "RegisterBrowserNodeRequest", true, "BrowserNode"),
    operation("reportBrowserNodePressure", "POST", "/api/v1/browser-nodes/{nodeId}:pressure", List.of("nodeId"), List.of(), List.of(), "RecordNodePressureRequest", true, "BrowserNode"),
    operation("listExtensionProfiles", "GET", "/api/v1/extensions", List.of(), List.of(), List.of(), "", false, "ExtensionProfileListResponse"),
    operation("upsertExtensionProfile", "PUT", "/api/v1/extensions/{extensionId}", List.of("extensionId"), List.of(), List.of(), "UpsertExtensionProfileRequest", true, "ExtensionProfile"),
    operation("recordExtensionProfileSample", "POST", "/api/v1/extensions/{extensionId}:sample", List.of("extensionId"), List.of(), List.of(), "RecordExtensionSampleRequest", true, "ExtensionProfile"),
    operation("getBrowserPlacement", "GET", "/api/v1/browser-placements/{sessionId}", List.of("sessionId"), List.of(), List.of(), "", false, "BrowserPlacement"),
    operation("getEnterpriseOverview", "GET", "/api/v1/enterprise/overview", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "EnterpriseOverview"),
    operation("listRuntimeValidations", "GET", "/api/v1/enterprise/runtime-validations", List.of(), List.of(), List.of(), "", false, "array<RuntimeValidation>"),
    operation("startRuntimeValidation", "POST", "/api/v1/enterprise/runtime-validations", List.of(), List.of(), List.of(), "StartRuntimeValidationRequest", true, "RuntimeValidation"),
    operation("completeRuntimeValidation", "POST", "/api/v1/enterprise/runtime-validations/{validationId}:complete", List.of("validationId"), List.of(), List.of(), "CompleteRuntimeValidationRequest", true, "RuntimeValidation"),
    operation("startRuntimeValidationMatrix", "POST", "/api/v1/enterprise/runtime-validation-matrices", List.of(), List.of(), List.of(), "StartRuntimeValidationMatrixRequest", true, "array<RuntimeValidation>"),
    operation("claimRuntimeValidationJob", "POST", "/api/v1/enterprise/runtime-validation-jobs:claim", List.of(), List.of(), List.of(), "ClaimRuntimeValidationJobRequest", true, "RuntimeValidationJobClaim"),
    operation("startClaimedRuntimeValidationJob", "POST", "/api/v1/enterprise/runtime-validation-jobs/{validationId}:start", List.of("validationId"), List.of(), List.of(), "RuntimeValidationJobClaimRequest", true, "RuntimeValidationJob"),
    operation("heartbeatRuntimeValidationJob", "POST", "/api/v1/enterprise/runtime-validation-jobs/{validationId}:heartbeat", List.of("validationId"), List.of(), List.of(), "RuntimeValidationJobClaimRequest", true, "RuntimeValidationJob"),
    operation("completeRuntimeValidationJob", "POST", "/api/v1/enterprise/runtime-validation-jobs/{validationId}:complete", List.of("validationId"), List.of(), List.of(), "CompleteRuntimeValidationJobRequest", true, "RuntimeValidation"),
    operation("failRuntimeValidationJob", "POST", "/api/v1/enterprise/runtime-validation-jobs/{validationId}:fail", List.of("validationId"), List.of(), List.of(), "FailRuntimeValidationJobRequest", true, "RuntimeValidation"),
    operation("listEnterpriseCostRates", "GET", "/api/v1/enterprise/cost-rates", List.of(), List.of(), List.of(), "", false, "array<CostRate>"),
    operation("createEnterpriseCostRate", "POST", "/api/v1/enterprise/cost-rates", List.of(), List.of(), List.of(), "CreateCostRateRequest", true, "CostRate"),
    operation("explainSessionCost", "GET", "/api/v1/enterprise/sessions/{sessionId}/cost-explanation", List.of("sessionId"), List.of(), List.of("X-Tenant-Id"), "", false, "SessionCostExplanation"),
    operation("getTenantMediaQuota", "GET", "/api/v1/enterprise/media-quota", List.of(), List.of(), List.of(), "", false, "MediaQuota"),
    operation("upsertTenantMediaQuota", "PUT", "/api/v1/enterprise/media-quota", List.of(), List.of(), List.of(), "UpsertMediaQuotaRequest", true, "MediaQuota"),
    operation("upsertSloPolicy", "PUT", "/api/v1/enterprise/slo-policy", List.of(), List.of(), List.of("X-Tenant-Id"), "UpsertSloPolicyRequest", true, "ErrorBudget"),
    operation("getErrorBudget", "GET", "/api/v1/enterprise/error-budget", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "ErrorBudget"),
    operation("getReleaseFreezeState", "GET", "/api/v1/enterprise/release-freeze", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "ReleaseFreeze"),
    operation("recordServiceLevelEvent", "POST", "/api/v1/enterprise/service-level-events", List.of(), List.of(), List.of("X-Tenant-Id"), "RecordServiceLevelEventRequest", true, "ErrorBudget"),
    operation("listSlaExclusions", "GET", "/api/v1/enterprise/sla-exclusions", List.of(), List.of(), List.of(), "", false, "array<SlaExclusion>"),
    operation("upsertSlaExclusion", "PUT", "/api/v1/enterprise/sla-exclusions/{exclusionCode}", List.of("exclusionCode"), List.of(), List.of(), "UpsertSlaExclusionRequest", true, "SlaExclusion"),
    operation("listRetentionPolicies", "GET", "/api/v1/enterprise/retention-policies", List.of(), List.of(), List.of("X-Tenant-Id"), "", false, "array<RetentionPolicy>"),
    operation("upsertRetentionPolicy", "PUT", "/api/v1/enterprise/retention-policies", List.of(), List.of(), List.of("X-Tenant-Id"), "UpsertRetentionPolicyRequest", true, "RetentionPolicy"),
    operation("createRetentionDeletionReceipt", "POST", "/api/v1/enterprise/retention-deletion-receipts", List.of(), List.of(), List.of(), "CreateDeletionReceiptRequest", true, "DeletionReceipt"),
    operation("listLicenseInventory", "GET", "/api/v1/enterprise/license-inventory", List.of(), List.of(), List.of(), "", false, "array<LicenseInventory>"),
    operation("upsertLicenseInventory", "PUT", "/api/v1/enterprise/license-inventory/{componentId}", List.of("componentId"), List.of(), List.of(), "UpsertLicenseInventoryRequest", true, "LicenseInventory"),
    operation("generateAuditExportManifest", "POST", "/api/v1/enterprise/audit-exports", List.of(), List.of("fromSequence", "toSequence"), List.of(), "", false, "AuditExportManifest"),
    operation("listEnterpriseRegions", "GET", "/api/v1/enterprise/regions", List.of(), List.of(), List.of(), "", false, "array<EnterpriseRegion>"),
    operation("upsertEnterpriseRegion", "PUT", "/api/v1/enterprise/regions/{regionId}", List.of("regionId"), List.of(), List.of(), "UpsertRegionRequest", true, "EnterpriseRegion"),
    operation("listRecoveryGameDays", "GET", "/api/v1/enterprise/recovery-gamedays", List.of(), List.of(), List.of(), "", false, "array<RecoveryGameDay>"),
    operation("startRecoveryGameDay", "POST", "/api/v1/enterprise/recovery-gamedays", List.of(), List.of(), List.of(), "StartRecoveryGameDayRequest", true, "RecoveryGameDay"),
    operation("completeRecoveryGameDay", "POST", "/api/v1/enterprise/recovery-gamedays/{gameDayId}:complete", List.of("gameDayId"), List.of(), List.of(), "CompleteRecoveryGameDayRequest", true, "RecoveryGameDay"),
    operation("getRecoveryGameDay", "GET", "/api/v1/enterprise/recovery-gamedays/{gameDayId}", List.of("gameDayId"), List.of(), List.of(), "", false, "RecoveryGameDay"),
    operation("listRecoveryGameDayEvents", "GET", "/api/v1/enterprise/recovery-gamedays/{gameDayId}/events", List.of("gameDayId"), List.of("cursor", "limit"), List.of(), "", false, "RecoveryGameDayEventPage"),
    operation("listRecoveryGameDayTrends", "GET", "/api/v1/enterprise/recovery-gameday-trends", List.of(), List.of("windowDays"), List.of(), "", false, "array<RecoveryGameDayTrend>"),
    operation("generateRecoveryGameDayReport", "POST", "/api/v1/enterprise/recovery-gamedays/{gameDayId}/exports", List.of("gameDayId"), List.of(), List.of(), "", false, "RecoveryGameDayReportExport"),
    operation("getRecoveryGameDayReport", "GET", "/api/v1/enterprise/recovery-gameday-exports/{exportId}", List.of("exportId"), List.of(), List.of(), "", false, "RecoveryGameDayReportExport"),
    operation("listRecoveryGameDayRemediations", "GET", "/api/v1/enterprise/recovery-gameday-remediations", List.of(), List.of("state"), List.of(), "", false, "array<RecoveryGameDayRemediation>"),
    operation("updateRecoveryGameDayRemediation", "PUT", "/api/v1/enterprise/recovery-gameday-remediations/{ticketId}", List.of("ticketId"), List.of(), List.of(), "UpdateRecoveryGameDayRemediationRequest", true, "RecoveryGameDayRemediation"),
    operation("abortRecoveryGameDay", "POST", "/api/v1/enterprise/recovery-gamedays/{gameDayId}:abort", List.of("gameDayId"), List.of(), List.of(), "", false, "RecoveryGameDay"),
    operation("claimRecoveryGameDayJob", "POST", "/api/v1/enterprise/recovery-gameday-jobs:claim", List.of(), List.of(), List.of(), "ClaimRecoveryGameDayJobRequest", true, "RecoveryGameDayJobClaim"),
    operation("startRecoveryGameDayJob", "POST", "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:start", List.of("gameDayId"), List.of(), List.of(), "RecoveryGameDayJobClaimRequest", true, "RecoveryGameDayJob"),
    operation("heartbeatRecoveryGameDayJob", "POST", "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:heartbeat", List.of("gameDayId"), List.of(), List.of(), "RecoveryGameDayJobClaimRequest", true, "RecoveryGameDayJob"),
    operation("updateRecoveryGameDayJobStage", "POST", "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:stage", List.of("gameDayId"), List.of(), List.of(), "UpdateRecoveryGameDayStageRequest", true, "RecoveryGameDayJob"),
    operation("completeRecoveryGameDayJob", "POST", "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:complete", List.of("gameDayId"), List.of(), List.of(), "CompleteRecoveryGameDayJobRequest", true, "RecoveryGameDay"),
    operation("failRecoveryGameDayJob", "POST", "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:fail", List.of("gameDayId"), List.of(), List.of(), "FailRecoveryGameDayJobRequest", true, "RecoveryGameDay"),
    operation("generateComplianceSnapshot", "POST", "/api/v1/enterprise/compliance-snapshots", List.of(), List.of("framework"), List.of("X-Tenant-Id"), "", false, "ComplianceSnapshot"),
    operation("listEnvironmentSavedViews", "GET", "/api/v1/environment-saved-views", List.of(), List.of(), List.of(), "", false, "EnvironmentSavedViewListResponse"),
    operation("createEnvironmentSavedView", "POST", "/api/v1/environment-saved-views", List.of(), List.of(), List.of("Idempotency-Key"), "CreateEnvironmentSavedViewRequest", true, "EnvironmentSavedView"),
    operation("updateEnvironmentSavedView", "PUT", "/api/v1/environment-saved-views/{savedViewId}", List.of("savedViewId"), List.of(), List.of("Idempotency-Key"), "UpdateEnvironmentSavedViewRequest", true, "EnvironmentSavedView"),
    operation("deleteEnvironmentSavedView", "DELETE", "/api/v1/environment-saved-views/{savedViewId}", List.of("savedViewId"), List.of("expectedVersion"), List.of("Idempotency-Key"), "", false, ""),
    operation("listEnvironmentImports", "GET", "/api/v1/environment-imports", List.of(), List.of(), List.of(), "", false, "EnvironmentImportListResponse"),
    operation("previewEnvironmentImport", "POST", "/api/v1/environment-imports:preview", List.of(), List.of(), List.of("Idempotency-Key"), "PreviewEnvironmentImportRequest", true, "EnvironmentImport"),
    operation("getEnvironmentImport", "GET", "/api/v1/environment-imports/{importId}", List.of("importId"), List.of(), List.of(), "", false, "EnvironmentImport"),
    operation("commitEnvironmentImport", "POST", "/api/v1/environment-imports/{importId}:commit", List.of("importId"), List.of(), List.of("Idempotency-Key"), "CommitEnvironmentImportRequest", true, "EnvironmentImport"),
    operation("listWorkspaceGroups", "GET", "/api/v1/groups", List.of(), List.of(), List.of(), "", false, "WorkspaceGroupListResponse"),
    operation("createWorkspaceGroup", "POST", "/api/v1/groups", List.of(), List.of(), List.of("Idempotency-Key"), "WorkspaceGroupRequest", true, "WorkspaceGroup"),
    operation("updateWorkspaceGroup", "PUT", "/api/v1/groups/{groupId}", List.of("groupId"), List.of(), List.of("Idempotency-Key"), "WorkspaceGroupRequest", true, "WorkspaceGroup"),
    operation("deleteWorkspaceGroup", "DELETE", "/api/v1/groups/{groupId}", List.of("groupId"), List.of(), List.of("Idempotency-Key"), "", false, ""),
    operation("assignSessionToWorkspaceGroup", "PUT", "/api/v1/groups/{groupId}/sessions/{sessionId}", List.of("groupId", "sessionId"), List.of(), List.of("Idempotency-Key"), "", false, "WorkspaceGroup"),
    operation("unassignSessionFromWorkspaceGroup", "DELETE", "/api/v1/groups/{groupId}/sessions/{sessionId}", List.of("groupId", "sessionId"), List.of(), List.of("Idempotency-Key"), "", false, "WorkspaceGroup"),
    operation("listWorkspaceTags", "GET", "/api/v1/tags", List.of(), List.of(), List.of(), "", false, "WorkspaceTagListResponse"),
    operation("createWorkspaceTag", "POST", "/api/v1/tags", List.of(), List.of(), List.of("Idempotency-Key"), "WorkspaceTagRequest", true, "WorkspaceTag"),
    operation("updateWorkspaceTag", "PUT", "/api/v1/tags/{tagId}", List.of("tagId"), List.of(), List.of("Idempotency-Key"), "WorkspaceTagRequest", true, "WorkspaceTag"),
    operation("deleteWorkspaceTag", "DELETE", "/api/v1/tags/{tagId}", List.of("tagId"), List.of(), List.of("Idempotency-Key"), "", false, ""),
    operation("assignSessionToWorkspaceTag", "PUT", "/api/v1/tags/{tagId}/sessions/{sessionId}", List.of("sessionId", "tagId"), List.of(), List.of("Idempotency-Key"), "", false, "WorkspaceTag"),
    operation("unassignSessionFromWorkspaceTag", "DELETE", "/api/v1/tags/{tagId}/sessions/{sessionId}", List.of("sessionId", "tagId"), List.of(), List.of("Idempotency-Key"), "", false, "WorkspaceTag"),
    operation("listWorkspaceBatchOperations", "GET", "/api/v1/workspace-batch-operations", List.of(), List.of("limit"), List.of(), "", false, "WorkspaceBatchOperationListResponse"),
    operation("createWorkspaceBatchOperation", "POST", "/api/v1/workspace-batch-operations", List.of(), List.of(), List.of("Idempotency-Key"), "CreateWorkspaceBatchOperationRequest", true, "WorkspaceBatchOperation"),
    operation("getWorkspaceBatchOperation", "GET", "/api/v1/workspace-batch-operations/{batchOperationId}", List.of("batchOperationId"), List.of(), List.of(), "", false, "WorkspaceBatchOperation"),
    operation("cancelWorkspaceBatchOperation", "POST", "/api/v1/workspace-batch-operations/{batchOperationId}:cancel", List.of("batchOperationId"), List.of(), List.of("Idempotency-Key"), "CancelWorkspaceBatchOperationRequest", true, "WorkspaceBatchOperation"),
    operation("listWorkspaceMetadataBatchOperations", "GET", "/api/v1/workspace-metadata-batch-operations", List.of(), List.of("limit"), List.of(), "", false, "WorkspaceMetadataBatchOperationListResponse"),
    operation("createWorkspaceMetadataBatchOperation", "POST", "/api/v1/workspace-metadata-batch-operations", List.of(), List.of(), List.of("Idempotency-Key"), "CreateWorkspaceMetadataBatchOperationRequest", true, "WorkspaceMetadataBatchOperation"),
    operation("getWorkspaceMetadataBatchOperation", "GET", "/api/v1/workspace-metadata-batch-operations/{batchOperationId}", List.of("batchOperationId"), List.of(), List.of(), "", false, "WorkspaceMetadataBatchOperation"),
    operation("cancelWorkspaceMetadataBatchOperation", "POST", "/api/v1/workspace-metadata-batch-operations/{batchOperationId}:cancel", List.of("batchOperationId"), List.of(), List.of("Idempotency-Key"), "CancelWorkspaceBatchOperationRequest", true, "WorkspaceMetadataBatchOperation"),
    operation("getWorkspaceSettings", "GET", "/api/v1/workspace-settings", List.of(), List.of(), List.of(), "", false, "WorkspaceSettings"),
    operation("updateWorkspaceSettings", "PUT", "/api/v1/workspace-settings", List.of(), List.of(), List.of("Idempotency-Key"), "WorkspaceSettingsRequest", true, "WorkspaceSettings")
  ).stream().collect(Collectors.toUnmodifiableMap(Operation::operationId, value -> value));

  private final URI baseUri; private final String tenantId; private final String accessToken; private final String actorId; private final Transport transport;
  public BrowserCloudGeneratedClient(String baseUrl, String tenantId, String accessToken, String actorId, Transport transport) {
    URI parsed; try { parsed = URI.create(Objects.requireNonNull(baseUrl)); } catch (RuntimeException error) { throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL", error); }
    if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) || parsed.getHost() == null) throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL");
    if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
    this.baseUri = URI.create(baseUrl.replaceAll("/+$", "")); this.tenantId = tenantId; this.accessToken = accessToken; this.actorId = actorId; this.transport = transport == null ? httpTransport() : transport;
  }
  public Response call(String operationId, Request request) {
    var operation = OPERATIONS.get(operationId); if (operation == null) throw new IllegalArgumentException("unknown OpenAPI operation: " + operationId);
    Objects.requireNonNull(request, "request");
    var route = operation.path();
    for (var name : operation.pathParameters()) { var value = request.path().get(name); if (value == null) throw new IllegalArgumentException("missing path parameter " + name + " for " + operationId); route = route.replace("{" + name + "}", URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")); }
    for (var name : request.query().keySet()) if (!operation.queryParameters().contains(name)) throw new IllegalArgumentException("unknown query parameter " + name + " for " + operationId);
    if (operation.requestRequired() && request.jsonBody() == null) throw new IllegalArgumentException("request body is required for " + operationId);
    var controlledHeaders = List.of("authorization", "x-tenant-id", "x-actor-id");
    var allowedHeaders = operation.headerParameters().stream().map(String::toLowerCase).filter(name -> !controlledHeaders.contains(name)).collect(Collectors.toSet());
    for (var name : request.headers().keySet()) if (!allowedHeaders.contains(name.toLowerCase())) throw new IllegalArgumentException("unknown or identity-controlled header " + name + " for " + operationId);
    var query = request.query().entrySet().stream().flatMap(entry -> entry.getValue().stream().map(value -> encode(entry.getKey()) + "=" + encode(value))).collect(Collectors.joining("&"));
    var uri = URI.create(baseUri + route + (query.isEmpty() ? "" : "?" + query));
    var headers = new LinkedHashMap<String, String>(); headers.put("Accept", "application/json"); headers.put("Content-Type", "application/json");
    headers.putAll(request.headers());
    if (accessToken != null && !accessToken.isBlank()) headers.put("Authorization", "Bearer " + accessToken); else { headers.put("X-Tenant-Id", tenantId); if (actorId != null && !actorId.isBlank()) headers.put("X-Actor-Id", actorId); }
    try { var response = transport.send(operation.method(), uri, headers, request.jsonBody()); if (response.status() < 200 || response.status() >= 300) throw new ApiException(response.status(), jsonString(response.body(), "code", "UNKNOWN_ERROR"), jsonString(response.body(), "message", "HTTP " + response.status()), jsonString(response.body(), "requestId", null)); return response; }
    catch (IOException error) { throw new IllegalStateException("Browser Cloud request failed", error); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Browser Cloud request interrupted", error); }
  }
  private static Operation operation(String id, String method, String path, List<String> pathParameters, List<String> queryParameters, List<String> headerParameters, String requestSchema, boolean requestRequired, String responseSchema) { return new Operation(id, method, path, pathParameters, queryParameters, headerParameters, requestSchema, requestRequired, responseSchema); }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
  private static Transport httpTransport() { var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); return (method, uri, headers, body) -> { var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)); headers.forEach(builder::header); var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); return new Response(response.statusCode(), response.headers().map().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> String.join(",", entry.getValue()))), response.body()); }; }
  private static String jsonString(String json, String key, String fallback) { var matcher = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\s*:\s*\"((?:\\.|[^\"])*)\"").matcher(json == null ? "" : json); return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : fallback; }

  public Response getWorkspaceOverview(Request request) { return call("getWorkspaceOverview", request); }
  public Response streamWorkspaceOverviewChanges(Request request) { return call("streamWorkspaceOverviewChanges", request); }
  public Response getTenantCoordinatorRoute(Request request) { return call("getTenantCoordinatorRoute", request); }
  public Response getLatestTenantCoordinatorRouteMigration(Request request) { return call("getLatestTenantCoordinatorRouteMigration", request); }
  public Response requestTenantCoordinatorRouteMigration(Request request) { return call("requestTenantCoordinatorRouteMigration", request); }
  public Response globalSearch(Request request) { return call("globalSearch", request); }
  public Response listWorkspaceNotifications(Request request) { return call("listWorkspaceNotifications", request); }
  public Response streamWorkspaceNotificationChanges(Request request) { return call("streamWorkspaceNotificationChanges", request); }
  public Response updateWorkspaceNotificationReadCursor(Request request) { return call("updateWorkspaceNotificationReadCursor", request); }
  public Response getUserPreferences(Request request) { return call("getUserPreferences", request); }
  public Response updateUserPreferences(Request request) { return call("updateUserPreferences", request); }
  public Response listSessions(Request request) { return call("listSessions", request); }
  public Response createSession(Request request) { return call("createSession", request); }
  public Response getSession(Request request) { return call("getSession", request); }
  public Response getBrowserState(Request request) { return call("getBrowserState", request); }
  public Response getSessionResources(Request request) { return call("getSessionResources", request); }
  public Response listSessionResourceEvents(Request request) { return call("listSessionResourceEvents", request); }
  public Response listSessionEvidence(Request request) { return call("listSessionEvidence", request); }
  public Response captureSessionEvidence(Request request) { return call("captureSessionEvidence", request); }
  public Response getSessionEvidenceCapture(Request request) { return call("getSessionEvidenceCapture", request); }
  public Response createSessionEvidenceAccessGrant(Request request) { return call("createSessionEvidenceAccessGrant", request); }
  public Response redeemSessionEvidenceAccessGrant(Request request) { return call("redeemSessionEvidenceAccessGrant", request); }
  public Response streamSessionResourceChanges(Request request) { return call("streamSessionResourceChanges", request); }
  public Response streamSessionChanges(Request request) { return call("streamSessionChanges", request); }
  public Response getSessionSafePoint(Request request) { return call("getSessionSafePoint", request); }
  public Response listSessionSafetyLeases(Request request) { return call("listSessionSafetyLeases", request); }
  public Response acquireSessionSafetyLease(Request request) { return call("acquireSessionSafetyLease", request); }
  public Response renewSessionSafetyLease(Request request) { return call("renewSessionSafetyLease", request); }
  public Response releaseSessionSafetyLease(Request request) { return call("releaseSessionSafetyLease", request); }
  public Response getLatestSessionMigration(Request request) { return call("getLatestSessionMigration", request); }
  public Response rebindSessionProxy(Request request) { return call("rebindSessionProxy", request); }
  public Response getLatestSessionProxyRebind(Request request) { return call("getLatestSessionProxyRebind", request); }
  public Response getBusinessRecoveryValidation(Request request) { return call("getBusinessRecoveryValidation", request); }
  public Response validateBusinessRecovery(Request request) { return call("validateBusinessRecovery", request); }
  public Response listBusinessRecoveryProviderEvidence(Request request) { return call("listBusinessRecoveryProviderEvidence", request); }
  public Response submitBusinessRecoveryProviderEvidence(Request request) { return call("submitBusinessRecoveryProviderEvidence", request); }
  public Response getSessionApplicationBinding(Request request) { return call("getSessionApplicationBinding", request); }
  public Response rebindSessionApplicationContract(Request request) { return call("rebindSessionApplicationContract", request); }
  public Response listApplicationRecoveryContracts(Request request) { return call("listApplicationRecoveryContracts", request); }
  public Response getApplicationRecoveryContract(Request request) { return call("getApplicationRecoveryContract", request); }
  public Response upsertApplicationRecoveryContract(Request request) { return call("upsertApplicationRecoveryContract", request); }
  public Response listApplicationRecoveryContractRevisions(Request request) { return call("listApplicationRecoveryContractRevisions", request); }
  public Response diffApplicationRecoveryContractRevisions(Request request) { return call("diffApplicationRecoveryContractRevisions", request); }
  public Response restoreApplicationRecoveryContractRevision(Request request) { return call("restoreApplicationRecoveryContractRevision", request); }
  public Response requestApplicationRecoveryContractApproval(Request request) { return call("requestApplicationRecoveryContractApproval", request); }
  public Response approveApplicationRecoveryContract(Request request) { return call("approveApplicationRecoveryContract", request); }
  public Response rejectApplicationRecoveryContract(Request request) { return call("rejectApplicationRecoveryContract", request); }
  public Response updateSessionResourcePolicy(Request request) { return call("updateSessionResourcePolicy", request); }
  public Response startSession(Request request) { return call("startSession", request); }
  public Response resyncBrowserState(Request request) { return call("resyncBrowserState", request); }
  public Response terminateSession(Request request) { return call("terminateSession", request); }
  public Response requestHumanTakeover(Request request) { return call("requestHumanTakeover", request); }
  public Response releaseHumanTakeover(Request request) { return call("releaseHumanTakeover", request); }
  public Response createRemoteDesktopConnection(Request request) { return call("createRemoteDesktopConnection", request); }
  public Response listProfiles(Request request) { return call("listProfiles", request); }
  public Response createProfile(Request request) { return call("createProfile", request); }
  public Response getProfile(Request request) { return call("getProfile", request); }
  public Response listProfileImports(Request request) { return call("listProfileImports", request); }
  public Response importProfileCheckpoint(Request request) { return call("importProfileCheckpoint", request); }
  public Response getProfileImport(Request request) { return call("getProfileImport", request); }
  public Response getProxyOverview(Request request) { return call("getProxyOverview", request); }
  public Response listProxyBindings(Request request) { return call("listProxyBindings", request); }
  public Response createProxyBinding(Request request) { return call("createProxyBinding", request); }
  public Response updateProxyBinding(Request request) { return call("updateProxyBinding", request); }
  public Response deleteProxyBinding(Request request) { return call("deleteProxyBinding", request); }
  public Response createAgentTask(Request request) { return call("createAgentTask", request); }
  public Response listAgentTasks(Request request) { return call("listAgentTasks", request); }
  public Response listAgentTaskSummaries(Request request) { return call("listAgentTaskSummaries", request); }
  public Response getAgentTask(Request request) { return call("getAgentTask", request); }
  public Response executeAgentTask(Request request) { return call("executeAgentTask", request); }
  public Response claimAgentExecutionJob(Request request) { return call("claimAgentExecutionJob", request); }
  public Response startAgentExecutionJob(Request request) { return call("startAgentExecutionJob", request); }
  public Response heartbeatAgentExecutionJob(Request request) { return call("heartbeatAgentExecutionJob", request); }
  public Response driveAgentExecutionJob(Request request) { return call("driveAgentExecutionJob", request); }
  public Response failAgentExecutionJob(Request request) { return call("failAgentExecutionJob", request); }
  public Response claimAgentReviewJob(Request request) { return call("claimAgentReviewJob", request); }
  public Response startAgentReviewJob(Request request) { return call("startAgentReviewJob", request); }
  public Response heartbeatAgentReviewJob(Request request) { return call("heartbeatAgentReviewJob", request); }
  public Response completeAgentReviewJob(Request request) { return call("completeAgentReviewJob", request); }
  public Response failAgentReviewJob(Request request) { return call("failAgentReviewJob", request); }
  public Response approveAgentTask(Request request) { return call("approveAgentTask", request); }
  public Response rejectAgentTask(Request request) { return call("rejectAgentTask", request); }
  public Response acceptAgentHandoff(Request request) { return call("acceptAgentHandoff", request); }
  public Response rejectAgentHandoff(Request request) { return call("rejectAgentHandoff", request); }
  public Response listAuditEvents(Request request) { return call("listAuditEvents", request); }
  public Response listRuntimeBuilds(Request request) { return call("listRuntimeBuilds", request); }
  public Response requestRuntimePromotion(Request request) { return call("requestRuntimePromotion", request); }
  public Response requestRuntimeDisable(Request request) { return call("requestRuntimeDisable", request); }
  public Response listRuntimeReleaseRequests(Request request) { return call("listRuntimeReleaseRequests", request); }
  public Response approveRuntimeRelease(Request request) { return call("approveRuntimeRelease", request); }
  public Response rejectRuntimeRelease(Request request) { return call("rejectRuntimeRelease", request); }
  public Response listKeyRotationRequests(Request request) { return call("listKeyRotationRequests", request); }
  public Response requestKeyRotation(Request request) { return call("requestKeyRotation", request); }
  public Response approveKeyRotation(Request request) { return call("approveKeyRotation", request); }
  public Response completeKeyRotation(Request request) { return call("completeKeyRotation", request); }
  public Response revokeKeyRotation(Request request) { return call("revokeKeyRotation", request); }
  public Response listBreakGlassRequests(Request request) { return call("listBreakGlassRequests", request); }
  public Response requestBreakGlass(Request request) { return call("requestBreakGlass", request); }
  public Response approveBreakGlass(Request request) { return call("approveBreakGlass", request); }
  public Response rejectBreakGlass(Request request) { return call("rejectBreakGlass", request); }
  public Response revokeBreakGlass(Request request) { return call("revokeBreakGlass", request); }
  public Response reviewBreakGlass(Request request) { return call("reviewBreakGlass", request); }
  public Response startSecureDebug(Request request) { return call("startSecureDebug", request); }
  public Response listSecureDebugSessions(Request request) { return call("listSecureDebugSessions", request); }
  public Response readSecureDebugSnapshot(Request request) { return call("readSecureDebugSnapshot", request); }
  public Response endSecureDebug(Request request) { return call("endSecureDebug", request); }
  public Response listBrowserNodes(Request request) { return call("listBrowserNodes", request); }
  public Response registerBrowserNode(Request request) { return call("registerBrowserNode", request); }
  public Response reportBrowserNodePressure(Request request) { return call("reportBrowserNodePressure", request); }
  public Response listExtensionProfiles(Request request) { return call("listExtensionProfiles", request); }
  public Response upsertExtensionProfile(Request request) { return call("upsertExtensionProfile", request); }
  public Response recordExtensionProfileSample(Request request) { return call("recordExtensionProfileSample", request); }
  public Response getBrowserPlacement(Request request) { return call("getBrowserPlacement", request); }
  public Response getEnterpriseOverview(Request request) { return call("getEnterpriseOverview", request); }
  public Response listRuntimeValidations(Request request) { return call("listRuntimeValidations", request); }
  public Response startRuntimeValidation(Request request) { return call("startRuntimeValidation", request); }
  public Response completeRuntimeValidation(Request request) { return call("completeRuntimeValidation", request); }
  public Response startRuntimeValidationMatrix(Request request) { return call("startRuntimeValidationMatrix", request); }
  public Response claimRuntimeValidationJob(Request request) { return call("claimRuntimeValidationJob", request); }
  public Response startClaimedRuntimeValidationJob(Request request) { return call("startClaimedRuntimeValidationJob", request); }
  public Response heartbeatRuntimeValidationJob(Request request) { return call("heartbeatRuntimeValidationJob", request); }
  public Response completeRuntimeValidationJob(Request request) { return call("completeRuntimeValidationJob", request); }
  public Response failRuntimeValidationJob(Request request) { return call("failRuntimeValidationJob", request); }
  public Response listEnterpriseCostRates(Request request) { return call("listEnterpriseCostRates", request); }
  public Response createEnterpriseCostRate(Request request) { return call("createEnterpriseCostRate", request); }
  public Response explainSessionCost(Request request) { return call("explainSessionCost", request); }
  public Response getTenantMediaQuota(Request request) { return call("getTenantMediaQuota", request); }
  public Response upsertTenantMediaQuota(Request request) { return call("upsertTenantMediaQuota", request); }
  public Response upsertSloPolicy(Request request) { return call("upsertSloPolicy", request); }
  public Response getErrorBudget(Request request) { return call("getErrorBudget", request); }
  public Response getReleaseFreezeState(Request request) { return call("getReleaseFreezeState", request); }
  public Response recordServiceLevelEvent(Request request) { return call("recordServiceLevelEvent", request); }
  public Response listSlaExclusions(Request request) { return call("listSlaExclusions", request); }
  public Response upsertSlaExclusion(Request request) { return call("upsertSlaExclusion", request); }
  public Response listRetentionPolicies(Request request) { return call("listRetentionPolicies", request); }
  public Response upsertRetentionPolicy(Request request) { return call("upsertRetentionPolicy", request); }
  public Response createRetentionDeletionReceipt(Request request) { return call("createRetentionDeletionReceipt", request); }
  public Response listLicenseInventory(Request request) { return call("listLicenseInventory", request); }
  public Response upsertLicenseInventory(Request request) { return call("upsertLicenseInventory", request); }
  public Response generateAuditExportManifest(Request request) { return call("generateAuditExportManifest", request); }
  public Response listEnterpriseRegions(Request request) { return call("listEnterpriseRegions", request); }
  public Response upsertEnterpriseRegion(Request request) { return call("upsertEnterpriseRegion", request); }
  public Response listRecoveryGameDays(Request request) { return call("listRecoveryGameDays", request); }
  public Response startRecoveryGameDay(Request request) { return call("startRecoveryGameDay", request); }
  public Response completeRecoveryGameDay(Request request) { return call("completeRecoveryGameDay", request); }
  public Response getRecoveryGameDay(Request request) { return call("getRecoveryGameDay", request); }
  public Response listRecoveryGameDayEvents(Request request) { return call("listRecoveryGameDayEvents", request); }
  public Response listRecoveryGameDayTrends(Request request) { return call("listRecoveryGameDayTrends", request); }
  public Response generateRecoveryGameDayReport(Request request) { return call("generateRecoveryGameDayReport", request); }
  public Response getRecoveryGameDayReport(Request request) { return call("getRecoveryGameDayReport", request); }
  public Response listRecoveryGameDayRemediations(Request request) { return call("listRecoveryGameDayRemediations", request); }
  public Response updateRecoveryGameDayRemediation(Request request) { return call("updateRecoveryGameDayRemediation", request); }
  public Response abortRecoveryGameDay(Request request) { return call("abortRecoveryGameDay", request); }
  public Response claimRecoveryGameDayJob(Request request) { return call("claimRecoveryGameDayJob", request); }
  public Response startRecoveryGameDayJob(Request request) { return call("startRecoveryGameDayJob", request); }
  public Response heartbeatRecoveryGameDayJob(Request request) { return call("heartbeatRecoveryGameDayJob", request); }
  public Response updateRecoveryGameDayJobStage(Request request) { return call("updateRecoveryGameDayJobStage", request); }
  public Response completeRecoveryGameDayJob(Request request) { return call("completeRecoveryGameDayJob", request); }
  public Response failRecoveryGameDayJob(Request request) { return call("failRecoveryGameDayJob", request); }
  public Response generateComplianceSnapshot(Request request) { return call("generateComplianceSnapshot", request); }
  public Response listEnvironmentSavedViews(Request request) { return call("listEnvironmentSavedViews", request); }
  public Response createEnvironmentSavedView(Request request) { return call("createEnvironmentSavedView", request); }
  public Response updateEnvironmentSavedView(Request request) { return call("updateEnvironmentSavedView", request); }
  public Response deleteEnvironmentSavedView(Request request) { return call("deleteEnvironmentSavedView", request); }
  public Response listEnvironmentImports(Request request) { return call("listEnvironmentImports", request); }
  public Response previewEnvironmentImport(Request request) { return call("previewEnvironmentImport", request); }
  public Response getEnvironmentImport(Request request) { return call("getEnvironmentImport", request); }
  public Response commitEnvironmentImport(Request request) { return call("commitEnvironmentImport", request); }
  public Response listWorkspaceGroups(Request request) { return call("listWorkspaceGroups", request); }
  public Response createWorkspaceGroup(Request request) { return call("createWorkspaceGroup", request); }
  public Response updateWorkspaceGroup(Request request) { return call("updateWorkspaceGroup", request); }
  public Response deleteWorkspaceGroup(Request request) { return call("deleteWorkspaceGroup", request); }
  public Response assignSessionToWorkspaceGroup(Request request) { return call("assignSessionToWorkspaceGroup", request); }
  public Response unassignSessionFromWorkspaceGroup(Request request) { return call("unassignSessionFromWorkspaceGroup", request); }
  public Response listWorkspaceTags(Request request) { return call("listWorkspaceTags", request); }
  public Response createWorkspaceTag(Request request) { return call("createWorkspaceTag", request); }
  public Response updateWorkspaceTag(Request request) { return call("updateWorkspaceTag", request); }
  public Response deleteWorkspaceTag(Request request) { return call("deleteWorkspaceTag", request); }
  public Response assignSessionToWorkspaceTag(Request request) { return call("assignSessionToWorkspaceTag", request); }
  public Response unassignSessionFromWorkspaceTag(Request request) { return call("unassignSessionFromWorkspaceTag", request); }
  public Response listWorkspaceBatchOperations(Request request) { return call("listWorkspaceBatchOperations", request); }
  public Response createWorkspaceBatchOperation(Request request) { return call("createWorkspaceBatchOperation", request); }
  public Response getWorkspaceBatchOperation(Request request) { return call("getWorkspaceBatchOperation", request); }
  public Response cancelWorkspaceBatchOperation(Request request) { return call("cancelWorkspaceBatchOperation", request); }
  public Response listWorkspaceMetadataBatchOperations(Request request) { return call("listWorkspaceMetadataBatchOperations", request); }
  public Response createWorkspaceMetadataBatchOperation(Request request) { return call("createWorkspaceMetadataBatchOperation", request); }
  public Response getWorkspaceMetadataBatchOperation(Request request) { return call("getWorkspaceMetadataBatchOperation", request); }
  public Response cancelWorkspaceMetadataBatchOperation(Request request) { return call("cancelWorkspaceMetadataBatchOperation", request); }
  public Response getWorkspaceSettings(Request request) { return call("getWorkspaceSettings", request); }
  public Response updateWorkspaceSettings(Request request) { return call("updateWorkspaceSettings", request); }
}
