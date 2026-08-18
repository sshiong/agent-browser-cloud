"""Generated full-operation OpenAPI client. Do not edit."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Mapping

GENERATOR = 'browsercloud-multilang-generator@1'


@dataclass(frozen=True)
class Operation:
    operation_id: str
    method: str
    path: str
    path_parameters: tuple[str, ...]
    query_parameters: tuple[str, ...]
    header_parameters: tuple[str, ...]
    request_schema: str
    request_required: bool
    response_schema: str


@dataclass(frozen=True)
class ApiError(Exception):
    status: int
    code: str
    message: str
    request_id: str | None = None

    def __str__(self) -> str:
        request = f" request_id={self.request_id}" if self.request_id else ""
        return f"{self.status} {self.code}: {self.message}{request}"


Transport = Callable[[str, str, Mapping[str, str], bytes | None], tuple[int, Mapping[str, str], bytes]]

OPERATIONS: dict[str, Operation] = {
    'getWorkspaceOverview': Operation('getWorkspaceOverview', 'GET', '/api/v1/workspace-overview', (), (), (), '', False, 'WorkspaceOverview'),
    'streamWorkspaceOverviewChanges': Operation('streamWorkspaceOverviewChanges', 'GET', '/api/v1/workspace-overview/event-stream', (), (), ('Last-Event-ID',), '', False, 'string'),
    'getTenantCoordinatorRoute': Operation('getTenantCoordinatorRoute', 'GET', '/api/v1/coordinator/tenant-route', (), (), (), '', False, 'TenantRoute'),
    'getLatestTenantCoordinatorRouteMigration': Operation('getLatestTenantCoordinatorRouteMigration', 'GET', '/api/v1/coordinator/tenant-route/migration', (), (), (), '', False, 'TenantRouteMigration'),
    'requestTenantCoordinatorRouteMigration': Operation('requestTenantCoordinatorRouteMigration', 'POST', '/api/v1/coordinator/tenant-route/migrations', (), (), ('Idempotency-Key',), 'RequestTenantRouteMigration', True, 'TenantRouteMigration'),
    'globalSearch': Operation('globalSearch', 'GET', '/api/v1/search', (), ('limit', 'q', 'types'), (), '', False, 'GlobalSearchResponse'),
    'listWorkspaceNotifications': Operation('listWorkspaceNotifications', 'GET', '/api/v1/notifications', (), ('beforeSequence', 'limit'), (), '', False, 'WorkspaceNotificationListResponse'),
    'streamWorkspaceNotificationChanges': Operation('streamWorkspaceNotificationChanges', 'GET', '/api/v1/notifications/event-stream', (), (), ('Last-Event-ID',), '', False, 'string'),
    'updateWorkspaceNotificationReadCursor': Operation('updateWorkspaceNotificationReadCursor', 'PATCH', '/api/v1/notifications/read-cursor', (), (), (), 'UpdateNotificationReadCursorRequest', True, 'WorkspaceNotificationReadState'),
    'getUserPreferences': Operation('getUserPreferences', 'GET', '/api/v1/user-preferences', (), (), (), '', False, 'UserPreferences'),
    'updateUserPreferences': Operation('updateUserPreferences', 'PUT', '/api/v1/user-preferences', (), (), (), 'UpdateUserPreferencesRequest', True, 'UserPreferences'),
    'listSessions': Operation('listSessions', 'GET', '/api/v1/sessions', (), ('groupId', 'limit', 'offset', 'q', 'state', 'tagId', 'tagMatch'), ('X-Tenant-Id',), '', False, 'SessionListResponse'),
    'createSession': Operation('createSession', 'POST', '/api/v1/sessions', (), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CreateSessionRequest', True, 'CreateSessionResponse'),
    'getSession': Operation('getSession', 'GET', '/api/v1/sessions/{sessionId}', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionView'),
    'getBrowserState': Operation('getBrowserState', 'GET', '/api/v1/sessions/{sessionId}/state', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'BrowserState'),
    'getSessionResources': Operation('getSessionResources', 'GET', '/api/v1/sessions/{sessionId}/resources', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionResource'),
    'listSessionResourceEvents': Operation('listSessionResourceEvents', 'GET', '/api/v1/sessions/{sessionId}/resource-events', ('sessionId',), ('limit', 'offset'), ('X-Tenant-Id',), '', False, 'ResourceEventList'),
    'listSessionEvidence': Operation('listSessionEvidence', 'GET', '/api/v1/sessions/{sessionId}/evidence', ('sessionId',), ('limit', 'offset'), ('X-Tenant-Id',), '', False, 'EvidenceList'),
    'listSessionRecordings': Operation('listSessionRecordings', 'GET', '/api/v1/sessions/{sessionId}/recordings', ('sessionId',), ('limit', 'offset'), ('X-Tenant-Id',), '', False, 'RecordingList'),
    'captureSessionEvidence': Operation('captureSessionEvidence', 'POST', '/api/v1/sessions/{sessionId}/evidence:capture', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CaptureEvidenceRequest', True, 'EvidenceCapture'),
    'getSessionEvidenceCapture': Operation('getSessionEvidenceCapture', 'GET', '/api/v1/sessions/{sessionId}/evidence-captures/{captureId}', ('captureId', 'sessionId'), (), ('X-Tenant-Id',), '', False, 'EvidenceCapture'),
    'createSessionEvidenceAccessGrant': Operation('createSessionEvidenceAccessGrant', 'POST', '/api/v1/sessions/{sessionId}/evidence/{evidenceId}/access-grants', ('evidenceId', 'sessionId'), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CreateEvidenceAccessGrantRequest', True, 'EvidenceAccessGrant'),
    'redeemSessionEvidenceAccessGrant': Operation('redeemSessionEvidenceAccessGrant', 'POST', '/api/v1/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem', ('grantId', 'sessionId'), (), ('X-Tenant-Id',), '', False, 'RedeemEvidenceAccessResponse'),
    'streamSessionResourceChanges': Operation('streamSessionResourceChanges', 'GET', '/api/v1/sessions/{sessionId}/resource-stream', ('sessionId',), (), ('Last-Event-ID', 'X-Tenant-Id'), '', False, 'string'),
    'streamSessionChanges': Operation('streamSessionChanges', 'GET', '/api/v1/sessions/{sessionId}/event-stream', ('sessionId',), (), ('Last-Event-ID', 'X-Tenant-Id'), '', False, 'string'),
    'getSessionSafePoint': Operation('getSessionSafePoint', 'GET', '/api/v1/sessions/{sessionId}/safe-point', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionSafePoint'),
    'listSessionSafetyLeases': Operation('listSessionSafetyLeases', 'GET', '/api/v1/sessions/{sessionId}/safety-leases', ('sessionId',), ('limit',), ('X-Tenant-Id',), '', False, 'SafetyLeaseList'),
    'acquireSessionSafetyLease': Operation('acquireSessionSafetyLease', 'POST', '/api/v1/sessions/{sessionId}/safety-leases', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CreateSafetyLeaseRequest', True, 'SafetyLease'),
    'renewSessionSafetyLease': Operation('renewSessionSafetyLease', 'PUT', '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}', ('leaseId', 'sessionId'), (), ('Idempotency-Key', 'X-Tenant-Id'), 'RenewSafetyLeaseRequest', True, 'SafetyLease'),
    'releaseSessionSafetyLease': Operation('releaseSessionSafetyLease', 'POST', '/api/v1/sessions/{sessionId}/safety-leases/{leaseId}:release', ('leaseId', 'sessionId'), (), ('Idempotency-Key', 'X-Tenant-Id'), '', False, 'SafetyLease'),
    'getLatestSessionMigration': Operation('getLatestSessionMigration', 'GET', '/api/v1/sessions/{sessionId}/migration', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionMigration'),
    'rebindSessionProxy': Operation('rebindSessionProxy', 'POST', '/api/v1/sessions/{sessionId}/proxy-binding:rebind', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'ProxyRebindRequest', True, 'ProxyRebindOperation'),
    'getLatestSessionProxyRebind': Operation('getLatestSessionProxyRebind', 'GET', '/api/v1/sessions/{sessionId}/proxy-rebind', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'ProxyRebind'),
    'getBusinessRecoveryValidation': Operation('getBusinessRecoveryValidation', 'GET', '/api/v1/sessions/{sessionId}/business-recovery', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'BusinessRecoveryValidation'),
    'validateBusinessRecovery': Operation('validateBusinessRecovery', 'POST', '/api/v1/sessions/{sessionId}/business-recovery:validate', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), '', False, 'BusinessRecoveryValidation'),
    'listBusinessRecoveryProviderEvidence': Operation('listBusinessRecoveryProviderEvidence', 'GET', '/api/v1/sessions/{sessionId}/business-recovery/provider-evidence', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'ProviderEvidenceListResponse'),
    'submitBusinessRecoveryProviderEvidence': Operation('submitBusinessRecoveryProviderEvidence', 'POST', '/api/v1/sessions/{sessionId}/business-recovery/provider-evidence', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'SubmitProviderEvidenceRequest', True, 'ProviderEvidence'),
    'getSessionApplicationBinding': Operation('getSessionApplicationBinding', 'GET', '/api/v1/sessions/{sessionId}/application-binding', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionApplicationBinding'),
    'rebindSessionApplicationContract': Operation('rebindSessionApplicationContract', 'POST', '/api/v1/sessions/{sessionId}/application-binding:rebind', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'RebindSessionApplicationRequest', True, 'SessionApplicationRebind'),
    'listApplicationRecoveryContracts': Operation('listApplicationRecoveryContracts', 'GET', '/api/v1/applications/recovery-contracts', (), (), ('X-Tenant-Id',), '', False, 'RecoveryContractListResponse'),
    'getApplicationRecoveryContract': Operation('getApplicationRecoveryContract', 'GET', '/api/v1/applications/{applicationId}/recovery-contract', ('applicationId',), (), ('X-Tenant-Id',), '', False, 'RecoveryContract'),
    'upsertApplicationRecoveryContract': Operation('upsertApplicationRecoveryContract', 'PUT', '/api/v1/applications/{applicationId}/recovery-contract', ('applicationId',), (), ('X-Tenant-Id',), 'UpsertRecoveryContractRequest', True, 'RecoveryContract'),
    'listApplicationRecoveryContractRevisions': Operation('listApplicationRecoveryContractRevisions', 'GET', '/api/v1/applications/{applicationId}/recovery-contract/revisions', ('applicationId',), (), ('X-Tenant-Id',), '', False, 'RecoveryContractRevisionListResponse'),
    'diffApplicationRecoveryContractRevisions': Operation('diffApplicationRecoveryContractRevisions', 'GET', '/api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff', ('applicationId', 'version'), ('compareToVersion',), ('X-Tenant-Id',), '', False, 'RecoveryContractDiff'),
    'restoreApplicationRecoveryContractRevision': Operation('restoreApplicationRecoveryContractRevision', 'POST', '/api/v1/applications/{applicationId}/recovery-contract:restore', ('applicationId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'RestoreRecoveryContractRevisionRequest', True, 'RecoveryContract'),
    'requestApplicationRecoveryContractApproval': Operation('requestApplicationRecoveryContractApproval', 'POST', '/api/v1/applications/{applicationId}/recovery-contract:request-approval', ('applicationId',), (), ('X-Tenant-Id',), 'RequestRecoveryContractApprovalRequest', True, 'RecoveryContractApproval'),
    'approveApplicationRecoveryContract': Operation('approveApplicationRecoveryContract', 'POST', '/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve', ('applicationId', 'approvalId'), (), ('X-Tenant-Id',), '', False, 'RecoveryContractApproval'),
    'rejectApplicationRecoveryContract': Operation('rejectApplicationRecoveryContract', 'POST', '/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject', ('applicationId', 'approvalId'), (), ('X-Tenant-Id',), '', False, 'RecoveryContractApproval'),
    'updateSessionResourcePolicy': Operation('updateSessionResourcePolicy', 'PATCH', '/api/v1/sessions/{sessionId}/resource-policy', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'ResourcePolicyRequest', True, 'ResourcePolicyOperation'),
    'startSession': Operation('startSession', 'POST', '/api/v1/sessions/{sessionId}:start', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'OperationResponse'),
    'resyncBrowserState': Operation('resyncBrowserState', 'POST', '/api/v1/sessions/{sessionId}:resync-state', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'StateResyncRequest', True, 'StateResyncResponse'),
    'terminateSession': Operation('terminateSession', 'POST', '/api/v1/sessions/{sessionId}:terminate', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'OperationResponse'),
    'requestHumanTakeover': Operation('requestHumanTakeover', 'POST', '/api/v1/sessions/{sessionId}:takeover', ('sessionId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'OperationResponse'),
    'releaseHumanTakeover': Operation('releaseHumanTakeover', 'POST', '/api/v1/sessions/{sessionId}:release-takeover', ('sessionId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'OperationResponse'),
    'createRemoteDesktopConnection': Operation('createRemoteDesktopConnection', 'POST', '/api/v1/sessions/{sessionId}:desktop-connection', ('sessionId',), ('viewOnly',), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'RemoteDesktopConnection'),
    'listRemoteDesktopParticipants': Operation('listRemoteDesktopParticipants', 'GET', '/api/v1/sessions/{sessionId}/desktop-participants', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'RemoteDesktopParticipantList'),
    'listRemoteDesktopParticipantHistory': Operation('listRemoteDesktopParticipantHistory', 'GET', '/api/v1/sessions/{sessionId}/desktop-participants/history', ('sessionId',), ('cursor', 'limit'), ('X-Tenant-Id',), '', False, 'RemoteDesktopParticipantHistoryPage'),
    'revokeRemoteDesktopParticipant': Operation('revokeRemoteDesktopParticipant', 'POST', '/api/v1/sessions/{sessionId}/desktop-participants/{connectionId}:revoke', ('connectionId', 'sessionId'), (), ('Idempotency-Key', 'X-Actor-Id', 'X-Tenant-Id'), '', False, 'RemoteDesktopParticipant'),
    'listProfiles': Operation('listProfiles', 'GET', '/api/v1/profiles', (), (), ('X-Tenant-Id',), '', False, 'ProfileListResponse'),
    'createProfile': Operation('createProfile', 'POST', '/api/v1/profiles', (), (), ('X-Tenant-Id',), 'CreateProfileRequest', True, 'Profile'),
    'getProfile': Operation('getProfile', 'GET', '/api/v1/profiles/{profileId}', ('profileId',), (), ('X-Tenant-Id',), '', False, 'Profile'),
    'getProfileWarmTierStatus': Operation('getProfileWarmTierStatus', 'GET', '/api/v1/profiles/{profileId}/warm-tier', ('profileId',), (), ('X-Tenant-Id',), '', False, 'ProfileWarmTierStatus'),
    'createProfileExportGrant': Operation('createProfileExportGrant', 'POST', '/api/v1/profiles/{profileId}/export-grants', ('profileId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CreateProfileExportGrantRequest', True, 'ProfileExportGrant'),
    'redeemProfileExportGrant': Operation('redeemProfileExportGrant', 'POST', '/api/v1/profiles/{profileId}/export-grants/{grantId}:redeem', ('grantId', 'profileId'), (), ('X-Tenant-Id',), '', False, 'RedeemProfileExportResponse'),
    'listProfileImports': Operation('listProfileImports', 'GET', '/api/v1/profile-imports', (), ('limit',), ('X-Tenant-Id',), '', False, 'ProfileImportListResponse'),
    'importProfileCheckpoint': Operation('importProfileCheckpoint', 'POST', '/api/v1/profile-imports', (), (), ('Idempotency-Key', 'X-Actor-Id', 'X-Tenant-Id'), 'object', True, 'ProfileImport'),
    'getProfileImport': Operation('getProfileImport', 'GET', '/api/v1/profile-imports/{importId}', ('importId',), (), ('X-Tenant-Id',), '', False, 'ProfileImport'),
    'getProxyOverview': Operation('getProxyOverview', 'GET', '/api/v1/proxies', (), (), ('X-Tenant-Id',), '', False, 'ProxyOverview'),
    'listProxyBindings': Operation('listProxyBindings', 'GET', '/api/v1/proxy-bindings', (), (), (), '', False, 'ProxyBindingList'),
    'createProxyBinding': Operation('createProxyBinding', 'POST', '/api/v1/proxy-bindings', (), (), ('Idempotency-Key',), 'ProxyBindingRequest', True, 'ProxyBinding'),
    'updateProxyBinding': Operation('updateProxyBinding', 'PUT', '/api/v1/proxy-bindings/{bindingProfileId}', ('bindingProfileId',), (), ('Idempotency-Key',), 'ProxyBindingRequest', True, 'ProxyBinding'),
    'deleteProxyBinding': Operation('deleteProxyBinding', 'DELETE', '/api/v1/proxy-bindings/{bindingProfileId}', ('bindingProfileId',), (), ('Idempotency-Key',), '', False, ''),
    'createAgentTask': Operation('createAgentTask', 'POST', '/api/v1/sessions/{sessionId}/agent-tasks', ('sessionId',), (), ('Idempotency-Key', 'X-Tenant-Id'), 'CreateAgentTaskRequest', True, 'AgentTask'),
    'listSessionChallenges': Operation('listSessionChallenges', 'GET', '/api/v1/sessions/{sessionId}/challenges', ('sessionId',), ('limit',), ('X-Tenant-Id',), '', False, 'ChallengeEventListResponse'),
    'getChallengeEvent': Operation('getChallengeEvent', 'GET', '/api/v1/challenges/{eventId}', ('eventId',), (), ('X-Tenant-Id',), '', False, 'ChallengeEvent'),
    'previewHumanAssist': Operation('previewHumanAssist', 'GET', '/api/v1/challenges/{eventId}/preview', ('eventId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'ChallengePreview'),
    'authorizeHumanAssist': Operation('authorizeHumanAssist', 'POST', '/api/v1/challenges/{eventId}/assist-authorizations', ('eventId',), (), ('Idempotency-Key', 'X-Actor-Id', 'X-Tenant-Id'), 'AuthorizeHumanAssistRequest', True, 'HumanAssistIntent'),
    'listAgentTasks': Operation('listAgentTasks', 'GET', '/api/v1/agent-tasks', (), ('limit', 'offset'), ('X-Tenant-Id',), '', False, 'AgentTaskListResponse'),
    'listAgentTaskSummaries': Operation('listAgentTaskSummaries', 'GET', '/api/v1/agent-task-summaries', (), ('cursor', 'limit'), ('X-Tenant-Id',), '', False, 'AgentTaskSummaryListResponse'),
    'getAgentTask': Operation('getAgentTask', 'GET', '/api/v1/agent-tasks/{taskId}', ('taskId',), (), ('X-Tenant-Id',), '', False, 'AgentTask'),
    'executeAgentTask': Operation('executeAgentTask', 'POST', '/api/v1/agent-tasks/{taskId}:execute', ('taskId',), (), ('Idempotency-Key', 'X-Tenant-Id'), '', False, 'AgentTask'),
    'claimAgentExecutionJob': Operation('claimAgentExecutionJob', 'POST', '/api/v1/agent-worker-jobs:claim', (), (), (), 'ClaimAgentExecutionJobRequest', True, 'AgentExecutionJobClaim'),
    'startAgentExecutionJob': Operation('startAgentExecutionJob', 'POST', '/api/v1/agent-worker-jobs/{jobId}:start', ('jobId',), (), (), 'AgentExecutionJobClaimRequest', True, 'AgentExecutionJob'),
    'heartbeatAgentExecutionJob': Operation('heartbeatAgentExecutionJob', 'POST', '/api/v1/agent-worker-jobs/{jobId}:heartbeat', ('jobId',), (), (), 'AgentExecutionJobClaimRequest', True, 'AgentExecutionJob'),
    'driveAgentExecutionJob': Operation('driveAgentExecutionJob', 'POST', '/api/v1/agent-worker-jobs/{jobId}:drive', ('jobId',), (), (), 'AgentExecutionJobClaimRequest', True, 'AgentExecutionJob'),
    'failAgentExecutionJob': Operation('failAgentExecutionJob', 'POST', '/api/v1/agent-worker-jobs/{jobId}:fail', ('jobId',), (), (), 'FailAgentExecutionJobRequest', True, 'AgentExecutionJob'),
    'claimAgentReviewJob': Operation('claimAgentReviewJob', 'POST', '/api/v1/agent-review-jobs:claim', (), (), (), 'ClaimAgentReviewJobRequest', True, 'AgentReviewJobClaim'),
    'startAgentReviewJob': Operation('startAgentReviewJob', 'POST', '/api/v1/agent-review-jobs/{jobId}:start', ('jobId',), (), (), 'AgentReviewJobClaimRequest', True, 'AgentReviewJob'),
    'heartbeatAgentReviewJob': Operation('heartbeatAgentReviewJob', 'POST', '/api/v1/agent-review-jobs/{jobId}:heartbeat', ('jobId',), (), (), 'AgentReviewJobClaimRequest', True, 'AgentReviewJob'),
    'completeAgentReviewJob': Operation('completeAgentReviewJob', 'POST', '/api/v1/agent-review-jobs/{jobId}:complete', ('jobId',), (), (), 'CompleteAgentReviewJobRequest', True, 'AgentReviewJob'),
    'failAgentReviewJob': Operation('failAgentReviewJob', 'POST', '/api/v1/agent-review-jobs/{jobId}:fail', ('jobId',), (), (), 'FailAgentReviewJobRequest', True, 'AgentReviewJob'),
    'approveAgentTask': Operation('approveAgentTask', 'POST', '/api/v1/agent-tasks/{taskId}:approve', ('taskId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'AgentTask'),
    'rejectAgentTask': Operation('rejectAgentTask', 'POST', '/api/v1/agent-tasks/{taskId}:reject', ('taskId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'AgentTask'),
    'acceptAgentHandoff': Operation('acceptAgentHandoff', 'POST', '/api/v1/agent-tasks/{taskId}:accept-handoff', ('taskId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'AgentTask'),
    'rejectAgentHandoff': Operation('rejectAgentHandoff', 'POST', '/api/v1/agent-tasks/{taskId}:reject-handoff', ('taskId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'AgentTask'),
    'listAuditEvents': Operation('listAuditEvents', 'GET', '/api/v1/audit-events', (), ('eventType', 'limit', 'offset', 'sessionId'), ('X-Tenant-Id',), '', False, 'AuditEventListResponse'),
    'streamAuditEventChanges': Operation('streamAuditEventChanges', 'GET', '/api/v1/audit-events/event-stream', (), (), ('Last-Event-ID', 'X-Tenant-Id'), '', False, 'string'),
    'listRuntimeBuilds': Operation('listRuntimeBuilds', 'GET', '/api/v1/runtime-builds', (), (), ('X-Tenant-Id',), '', False, 'RuntimeBuildListResponse'),
    'requestRuntimePromotion': Operation('requestRuntimePromotion', 'POST', '/api/v1/runtime-builds/{buildId}:promote', ('buildId',), (), ('X-Actor-Id', 'X-Tenant-Id'), 'CreateRuntimeReleaseRequest', True, 'RuntimeReleaseRequest'),
    'requestRuntimeDisable': Operation('requestRuntimeDisable', 'POST', '/api/v1/runtime-builds/{buildId}:disable', ('buildId',), (), ('X-Actor-Id', 'X-Tenant-Id'), 'CreateRuntimeDisableRequest', True, 'RuntimeReleaseRequest'),
    'listRuntimeReleaseRequests': Operation('listRuntimeReleaseRequests', 'GET', '/api/v1/runtime-release-requests', (), (), ('X-Tenant-Id',), '', False, 'RuntimeReleaseRequestListResponse'),
    'approveRuntimeRelease': Operation('approveRuntimeRelease', 'POST', '/api/v1/runtime-release-requests/{releaseId}:approve', ('releaseId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'RuntimeReleaseRequest'),
    'rejectRuntimeRelease': Operation('rejectRuntimeRelease', 'POST', '/api/v1/runtime-release-requests/{releaseId}:reject', ('releaseId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'RuntimeReleaseRequest'),
    'listKeyRotationRequests': Operation('listKeyRotationRequests', 'GET', '/api/v1/key-rotation-requests', (), (), ('X-Tenant-Id',), '', False, 'KeyRotationRequestListResponse'),
    'requestKeyRotation': Operation('requestKeyRotation', 'POST', '/api/v1/key-rotation-requests', (), (), ('X-Actor-Id', 'X-Tenant-Id'), 'CreateKeyRotationRequest', True, 'KeyRotationRequest'),
    'approveKeyRotation': Operation('approveKeyRotation', 'POST', '/api/v1/key-rotation-requests/{rotationId}:approve', ('rotationId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'KeyRotationRequest'),
    'completeKeyRotation': Operation('completeKeyRotation', 'POST', '/api/v1/key-rotation-requests/{rotationId}:complete', ('rotationId',), (), ('X-Actor-Id', 'X-Tenant-Id'), 'CompleteKeyRotationRequest', True, 'KeyRotationRequest'),
    'revokeKeyRotation': Operation('revokeKeyRotation', 'POST', '/api/v1/key-rotation-requests/{rotationId}:revoke', ('rotationId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'KeyRotationRequest'),
    'listBreakGlassRequests': Operation('listBreakGlassRequests', 'GET', '/api/v1/break-glass-requests', (), (), ('X-Tenant-Id',), '', False, 'BreakGlassRequestListResponse'),
    'requestBreakGlass': Operation('requestBreakGlass', 'POST', '/api/v1/break-glass-requests', (), (), ('X-Actor-Id', 'X-Tenant-Id'), 'CreateBreakGlassRequest', True, 'BreakGlassRequest'),
    'approveBreakGlass': Operation('approveBreakGlass', 'POST', '/api/v1/break-glass-requests/{requestId}:approve', ('requestId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'BreakGlassRequest'),
    'rejectBreakGlass': Operation('rejectBreakGlass', 'POST', '/api/v1/break-glass-requests/{requestId}:reject', ('requestId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'BreakGlassRequest'),
    'revokeBreakGlass': Operation('revokeBreakGlass', 'POST', '/api/v1/break-glass-requests/{requestId}:revoke', ('requestId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'BreakGlassRequest'),
    'reviewBreakGlass': Operation('reviewBreakGlass', 'POST', '/api/v1/break-glass-requests/{requestId}:review', ('requestId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'BreakGlassRequest'),
    'startSecureDebug': Operation('startSecureDebug', 'POST', '/api/v1/break-glass-requests/{requestId}:start-secure-debug', ('requestId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'SecureDebugSession'),
    'listSecureDebugSessions': Operation('listSecureDebugSessions', 'GET', '/api/v1/secure-debug-sessions', (), (), ('X-Tenant-Id',), '', False, 'SecureDebugSessionListResponse'),
    'readSecureDebugSnapshot': Operation('readSecureDebugSnapshot', 'GET', '/api/v1/secure-debug-sessions/{debugSessionId}/snapshot', ('debugSessionId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'SecureDebugSnapshot'),
    'endSecureDebug': Operation('endSecureDebug', 'POST', '/api/v1/secure-debug-sessions/{debugSessionId}:end', ('debugSessionId',), (), ('X-Actor-Id', 'X-Tenant-Id'), '', False, 'SecureDebugSession'),
    'listBrowserNodes': Operation('listBrowserNodes', 'GET', '/api/v1/browser-nodes', (), (), (), '', False, 'BrowserNodeListResponse'),
    'registerBrowserNode': Operation('registerBrowserNode', 'PUT', '/api/v1/browser-nodes/{nodeId}', ('nodeId',), (), (), 'RegisterBrowserNodeRequest', True, 'BrowserNode'),
    'reportBrowserNodePressure': Operation('reportBrowserNodePressure', 'POST', '/api/v1/browser-nodes/{nodeId}:pressure', ('nodeId',), (), (), 'RecordNodePressureRequest', True, 'BrowserNode'),
    'listExtensionProfiles': Operation('listExtensionProfiles', 'GET', '/api/v1/extensions', (), (), (), '', False, 'ExtensionProfileListResponse'),
    'upsertExtensionProfile': Operation('upsertExtensionProfile', 'PUT', '/api/v1/extensions/{extensionId}', ('extensionId',), (), (), 'UpsertExtensionProfileRequest', True, 'ExtensionProfile'),
    'recordExtensionProfileSample': Operation('recordExtensionProfileSample', 'POST', '/api/v1/extensions/{extensionId}:sample', ('extensionId',), (), (), 'RecordExtensionSampleRequest', True, 'ExtensionProfile'),
    'getBrowserPlacement': Operation('getBrowserPlacement', 'GET', '/api/v1/browser-placements/{sessionId}', ('sessionId',), (), (), '', False, 'BrowserPlacement'),
    'getEnterpriseOverview': Operation('getEnterpriseOverview', 'GET', '/api/v1/enterprise/overview', (), (), ('X-Tenant-Id',), '', False, 'EnterpriseOverview'),
    'streamEnterpriseOverviewChanges': Operation('streamEnterpriseOverviewChanges', 'GET', '/api/v1/enterprise/overview/event-stream', (), (), ('Last-Event-ID', 'X-Tenant-Id'), '', False, 'string'),
    'listRuntimeValidations': Operation('listRuntimeValidations', 'GET', '/api/v1/enterprise/runtime-validations', (), (), (), '', False, 'array<RuntimeValidation>'),
    'startRuntimeValidation': Operation('startRuntimeValidation', 'POST', '/api/v1/enterprise/runtime-validations', (), (), (), 'StartRuntimeValidationRequest', True, 'RuntimeValidation'),
    'completeRuntimeValidation': Operation('completeRuntimeValidation', 'POST', '/api/v1/enterprise/runtime-validations/{validationId}:complete', ('validationId',), (), (), 'CompleteRuntimeValidationRequest', True, 'RuntimeValidation'),
    'startRuntimeValidationMatrix': Operation('startRuntimeValidationMatrix', 'POST', '/api/v1/enterprise/runtime-validation-matrices', (), (), (), 'StartRuntimeValidationMatrixRequest', True, 'array<RuntimeValidation>'),
    'claimRuntimeValidationJob': Operation('claimRuntimeValidationJob', 'POST', '/api/v1/enterprise/runtime-validation-jobs:claim', (), (), (), 'ClaimRuntimeValidationJobRequest', True, 'RuntimeValidationJobClaim'),
    'startClaimedRuntimeValidationJob': Operation('startClaimedRuntimeValidationJob', 'POST', '/api/v1/enterprise/runtime-validation-jobs/{validationId}:start', ('validationId',), (), (), 'RuntimeValidationJobClaimRequest', True, 'RuntimeValidationJob'),
    'heartbeatRuntimeValidationJob': Operation('heartbeatRuntimeValidationJob', 'POST', '/api/v1/enterprise/runtime-validation-jobs/{validationId}:heartbeat', ('validationId',), (), (), 'RuntimeValidationJobClaimRequest', True, 'RuntimeValidationJob'),
    'completeRuntimeValidationJob': Operation('completeRuntimeValidationJob', 'POST', '/api/v1/enterprise/runtime-validation-jobs/{validationId}:complete', ('validationId',), (), (), 'CompleteRuntimeValidationJobRequest', True, 'RuntimeValidation'),
    'failRuntimeValidationJob': Operation('failRuntimeValidationJob', 'POST', '/api/v1/enterprise/runtime-validation-jobs/{validationId}:fail', ('validationId',), (), (), 'FailRuntimeValidationJobRequest', True, 'RuntimeValidation'),
    'listEnterpriseCostRates': Operation('listEnterpriseCostRates', 'GET', '/api/v1/enterprise/cost-rates', (), (), (), '', False, 'array<CostRate>'),
    'createEnterpriseCostRate': Operation('createEnterpriseCostRate', 'POST', '/api/v1/enterprise/cost-rates', (), (), (), 'CreateCostRateRequest', True, 'CostRate'),
    'explainSessionCost': Operation('explainSessionCost', 'GET', '/api/v1/enterprise/sessions/{sessionId}/cost-explanation', ('sessionId',), (), ('X-Tenant-Id',), '', False, 'SessionCostExplanation'),
    'getTenantMediaQuota': Operation('getTenantMediaQuota', 'GET', '/api/v1/enterprise/media-quota', (), (), (), '', False, 'MediaQuota'),
    'upsertTenantMediaQuota': Operation('upsertTenantMediaQuota', 'PUT', '/api/v1/enterprise/media-quota', (), (), (), 'UpsertMediaQuotaRequest', True, 'MediaQuota'),
    'upsertSloPolicy': Operation('upsertSloPolicy', 'PUT', '/api/v1/enterprise/slo-policy', (), (), ('X-Tenant-Id',), 'UpsertSloPolicyRequest', True, 'ErrorBudget'),
    'getErrorBudget': Operation('getErrorBudget', 'GET', '/api/v1/enterprise/error-budget', (), (), ('X-Tenant-Id',), '', False, 'ErrorBudget'),
    'getReleaseFreezeState': Operation('getReleaseFreezeState', 'GET', '/api/v1/enterprise/release-freeze', (), (), ('X-Tenant-Id',), '', False, 'ReleaseFreeze'),
    'recordServiceLevelEvent': Operation('recordServiceLevelEvent', 'POST', '/api/v1/enterprise/service-level-events', (), (), ('X-Tenant-Id',), 'RecordServiceLevelEventRequest', True, 'ErrorBudget'),
    'listSlaExclusions': Operation('listSlaExclusions', 'GET', '/api/v1/enterprise/sla-exclusions', (), (), (), '', False, 'array<SlaExclusion>'),
    'upsertSlaExclusion': Operation('upsertSlaExclusion', 'PUT', '/api/v1/enterprise/sla-exclusions/{exclusionCode}', ('exclusionCode',), (), (), 'UpsertSlaExclusionRequest', True, 'SlaExclusion'),
    'listRetentionPolicies': Operation('listRetentionPolicies', 'GET', '/api/v1/enterprise/retention-policies', (), (), ('X-Tenant-Id',), '', False, 'array<RetentionPolicy>'),
    'upsertRetentionPolicy': Operation('upsertRetentionPolicy', 'PUT', '/api/v1/enterprise/retention-policies', (), (), ('X-Tenant-Id',), 'UpsertRetentionPolicyRequest', True, 'RetentionPolicy'),
    'createRetentionDeletionReceipt': Operation('createRetentionDeletionReceipt', 'POST', '/api/v1/enterprise/retention-deletion-receipts', (), (), (), 'CreateDeletionReceiptRequest', True, 'DeletionReceipt'),
    'listLicenseInventory': Operation('listLicenseInventory', 'GET', '/api/v1/enterprise/license-inventory', (), (), (), '', False, 'array<LicenseInventory>'),
    'upsertLicenseInventory': Operation('upsertLicenseInventory', 'PUT', '/api/v1/enterprise/license-inventory/{componentId}', ('componentId',), (), (), 'UpsertLicenseInventoryRequest', True, 'LicenseInventory'),
    'generateAuditExportManifest': Operation('generateAuditExportManifest', 'POST', '/api/v1/enterprise/audit-exports', (), ('fromSequence', 'toSequence'), (), '', False, 'AuditExportManifest'),
    'listEnterpriseRegions': Operation('listEnterpriseRegions', 'GET', '/api/v1/enterprise/regions', (), (), (), '', False, 'array<EnterpriseRegion>'),
    'upsertEnterpriseRegion': Operation('upsertEnterpriseRegion', 'PUT', '/api/v1/enterprise/regions/{regionId}', ('regionId',), (), (), 'UpsertRegionRequest', True, 'EnterpriseRegion'),
    'listRecoveryGameDays': Operation('listRecoveryGameDays', 'GET', '/api/v1/enterprise/recovery-gamedays', (), (), (), '', False, 'array<RecoveryGameDay>'),
    'startRecoveryGameDay': Operation('startRecoveryGameDay', 'POST', '/api/v1/enterprise/recovery-gamedays', (), (), (), 'StartRecoveryGameDayRequest', True, 'RecoveryGameDay'),
    'completeRecoveryGameDay': Operation('completeRecoveryGameDay', 'POST', '/api/v1/enterprise/recovery-gamedays/{gameDayId}:complete', ('gameDayId',), (), (), 'CompleteRecoveryGameDayRequest', True, 'RecoveryGameDay'),
    'getRecoveryGameDay': Operation('getRecoveryGameDay', 'GET', '/api/v1/enterprise/recovery-gamedays/{gameDayId}', ('gameDayId',), (), (), '', False, 'RecoveryGameDay'),
    'listRecoveryGameDayEvents': Operation('listRecoveryGameDayEvents', 'GET', '/api/v1/enterprise/recovery-gamedays/{gameDayId}/events', ('gameDayId',), ('cursor', 'limit'), (), '', False, 'RecoveryGameDayEventPage'),
    'listRecoveryGameDayTrends': Operation('listRecoveryGameDayTrends', 'GET', '/api/v1/enterprise/recovery-gameday-trends', (), ('windowDays',), (), '', False, 'array<RecoveryGameDayTrend>'),
    'generateRecoveryGameDayReport': Operation('generateRecoveryGameDayReport', 'POST', '/api/v1/enterprise/recovery-gamedays/{gameDayId}/exports', ('gameDayId',), (), (), '', False, 'RecoveryGameDayReportExport'),
    'getRecoveryGameDayReport': Operation('getRecoveryGameDayReport', 'GET', '/api/v1/enterprise/recovery-gameday-exports/{exportId}', ('exportId',), (), (), '', False, 'RecoveryGameDayReportExport'),
    'listRecoveryGameDayRemediations': Operation('listRecoveryGameDayRemediations', 'GET', '/api/v1/enterprise/recovery-gameday-remediations', (), ('state',), (), '', False, 'array<RecoveryGameDayRemediation>'),
    'updateRecoveryGameDayRemediation': Operation('updateRecoveryGameDayRemediation', 'PUT', '/api/v1/enterprise/recovery-gameday-remediations/{ticketId}', ('ticketId',), (), (), 'UpdateRecoveryGameDayRemediationRequest', True, 'RecoveryGameDayRemediation'),
    'abortRecoveryGameDay': Operation('abortRecoveryGameDay', 'POST', '/api/v1/enterprise/recovery-gamedays/{gameDayId}:abort', ('gameDayId',), (), (), '', False, 'RecoveryGameDay'),
    'claimRecoveryGameDayJob': Operation('claimRecoveryGameDayJob', 'POST', '/api/v1/enterprise/recovery-gameday-jobs:claim', (), (), (), 'ClaimRecoveryGameDayJobRequest', True, 'RecoveryGameDayJobClaim'),
    'startRecoveryGameDayJob': Operation('startRecoveryGameDayJob', 'POST', '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:start', ('gameDayId',), (), (), 'RecoveryGameDayJobClaimRequest', True, 'RecoveryGameDayJob'),
    'heartbeatRecoveryGameDayJob': Operation('heartbeatRecoveryGameDayJob', 'POST', '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:heartbeat', ('gameDayId',), (), (), 'RecoveryGameDayJobClaimRequest', True, 'RecoveryGameDayJob'),
    'updateRecoveryGameDayJobStage': Operation('updateRecoveryGameDayJobStage', 'POST', '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:stage', ('gameDayId',), (), (), 'UpdateRecoveryGameDayStageRequest', True, 'RecoveryGameDayJob'),
    'completeRecoveryGameDayJob': Operation('completeRecoveryGameDayJob', 'POST', '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:complete', ('gameDayId',), (), (), 'CompleteRecoveryGameDayJobRequest', True, 'RecoveryGameDay'),
    'failRecoveryGameDayJob': Operation('failRecoveryGameDayJob', 'POST', '/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:fail', ('gameDayId',), (), (), 'FailRecoveryGameDayJobRequest', True, 'RecoveryGameDay'),
    'generateComplianceSnapshot': Operation('generateComplianceSnapshot', 'POST', '/api/v1/enterprise/compliance-snapshots', (), ('framework',), ('X-Tenant-Id',), '', False, 'ComplianceSnapshot'),
    'listEnvironmentSavedViews': Operation('listEnvironmentSavedViews', 'GET', '/api/v1/environment-saved-views', (), (), (), '', False, 'EnvironmentSavedViewListResponse'),
    'createEnvironmentSavedView': Operation('createEnvironmentSavedView', 'POST', '/api/v1/environment-saved-views', (), (), ('Idempotency-Key',), 'CreateEnvironmentSavedViewRequest', True, 'EnvironmentSavedView'),
    'updateEnvironmentSavedView': Operation('updateEnvironmentSavedView', 'PUT', '/api/v1/environment-saved-views/{savedViewId}', ('savedViewId',), (), ('Idempotency-Key',), 'UpdateEnvironmentSavedViewRequest', True, 'EnvironmentSavedView'),
    'deleteEnvironmentSavedView': Operation('deleteEnvironmentSavedView', 'DELETE', '/api/v1/environment-saved-views/{savedViewId}', ('savedViewId',), ('expectedVersion',), ('Idempotency-Key',), '', False, ''),
    'listEnvironmentImports': Operation('listEnvironmentImports', 'GET', '/api/v1/environment-imports', (), (), (), '', False, 'EnvironmentImportListResponse'),
    'previewEnvironmentImport': Operation('previewEnvironmentImport', 'POST', '/api/v1/environment-imports:preview', (), (), ('Idempotency-Key',), 'PreviewEnvironmentImportRequest', True, 'EnvironmentImport'),
    'getEnvironmentImport': Operation('getEnvironmentImport', 'GET', '/api/v1/environment-imports/{importId}', ('importId',), (), (), '', False, 'EnvironmentImport'),
    'commitEnvironmentImport': Operation('commitEnvironmentImport', 'POST', '/api/v1/environment-imports/{importId}:commit', ('importId',), (), ('Idempotency-Key',), 'CommitEnvironmentImportRequest', True, 'EnvironmentImport'),
    'listWorkspaceGroups': Operation('listWorkspaceGroups', 'GET', '/api/v1/groups', (), (), (), '', False, 'WorkspaceGroupListResponse'),
    'createWorkspaceGroup': Operation('createWorkspaceGroup', 'POST', '/api/v1/groups', (), (), ('Idempotency-Key',), 'WorkspaceGroupRequest', True, 'WorkspaceGroup'),
    'updateWorkspaceGroup': Operation('updateWorkspaceGroup', 'PUT', '/api/v1/groups/{groupId}', ('groupId',), (), ('Idempotency-Key',), 'WorkspaceGroupRequest', True, 'WorkspaceGroup'),
    'deleteWorkspaceGroup': Operation('deleteWorkspaceGroup', 'DELETE', '/api/v1/groups/{groupId}', ('groupId',), (), ('Idempotency-Key',), '', False, ''),
    'assignSessionToWorkspaceGroup': Operation('assignSessionToWorkspaceGroup', 'PUT', '/api/v1/groups/{groupId}/sessions/{sessionId}', ('groupId', 'sessionId'), (), ('Idempotency-Key',), '', False, 'WorkspaceGroup'),
    'unassignSessionFromWorkspaceGroup': Operation('unassignSessionFromWorkspaceGroup', 'DELETE', '/api/v1/groups/{groupId}/sessions/{sessionId}', ('groupId', 'sessionId'), (), ('Idempotency-Key',), '', False, 'WorkspaceGroup'),
    'listWorkspaceTags': Operation('listWorkspaceTags', 'GET', '/api/v1/tags', (), (), (), '', False, 'WorkspaceTagListResponse'),
    'createWorkspaceTag': Operation('createWorkspaceTag', 'POST', '/api/v1/tags', (), (), ('Idempotency-Key',), 'WorkspaceTagRequest', True, 'WorkspaceTag'),
    'updateWorkspaceTag': Operation('updateWorkspaceTag', 'PUT', '/api/v1/tags/{tagId}', ('tagId',), (), ('Idempotency-Key',), 'WorkspaceTagRequest', True, 'WorkspaceTag'),
    'deleteWorkspaceTag': Operation('deleteWorkspaceTag', 'DELETE', '/api/v1/tags/{tagId}', ('tagId',), (), ('Idempotency-Key',), '', False, ''),
    'assignSessionToWorkspaceTag': Operation('assignSessionToWorkspaceTag', 'PUT', '/api/v1/tags/{tagId}/sessions/{sessionId}', ('sessionId', 'tagId'), (), ('Idempotency-Key',), '', False, 'WorkspaceTag'),
    'unassignSessionFromWorkspaceTag': Operation('unassignSessionFromWorkspaceTag', 'DELETE', '/api/v1/tags/{tagId}/sessions/{sessionId}', ('sessionId', 'tagId'), (), ('Idempotency-Key',), '', False, 'WorkspaceTag'),
    'listWorkspaceBatchOperations': Operation('listWorkspaceBatchOperations', 'GET', '/api/v1/workspace-batch-operations', (), ('limit',), (), '', False, 'WorkspaceBatchOperationListResponse'),
    'createWorkspaceBatchOperation': Operation('createWorkspaceBatchOperation', 'POST', '/api/v1/workspace-batch-operations', (), (), ('Idempotency-Key',), 'CreateWorkspaceBatchOperationRequest', True, 'WorkspaceBatchOperation'),
    'getWorkspaceBatchOperation': Operation('getWorkspaceBatchOperation', 'GET', '/api/v1/workspace-batch-operations/{batchOperationId}', ('batchOperationId',), (), (), '', False, 'WorkspaceBatchOperation'),
    'cancelWorkspaceBatchOperation': Operation('cancelWorkspaceBatchOperation', 'POST', '/api/v1/workspace-batch-operations/{batchOperationId}:cancel', ('batchOperationId',), (), ('Idempotency-Key',), 'CancelWorkspaceBatchOperationRequest', True, 'WorkspaceBatchOperation'),
    'listWorkspaceMetadataBatchOperations': Operation('listWorkspaceMetadataBatchOperations', 'GET', '/api/v1/workspace-metadata-batch-operations', (), ('limit',), (), '', False, 'WorkspaceMetadataBatchOperationListResponse'),
    'createWorkspaceMetadataBatchOperation': Operation('createWorkspaceMetadataBatchOperation', 'POST', '/api/v1/workspace-metadata-batch-operations', (), (), ('Idempotency-Key',), 'CreateWorkspaceMetadataBatchOperationRequest', True, 'WorkspaceMetadataBatchOperation'),
    'getWorkspaceMetadataBatchOperation': Operation('getWorkspaceMetadataBatchOperation', 'GET', '/api/v1/workspace-metadata-batch-operations/{batchOperationId}', ('batchOperationId',), (), (), '', False, 'WorkspaceMetadataBatchOperation'),
    'cancelWorkspaceMetadataBatchOperation': Operation('cancelWorkspaceMetadataBatchOperation', 'POST', '/api/v1/workspace-metadata-batch-operations/{batchOperationId}:cancel', ('batchOperationId',), (), ('Idempotency-Key',), 'CancelWorkspaceBatchOperationRequest', True, 'WorkspaceMetadataBatchOperation'),
    'getWorkspaceSettings': Operation('getWorkspaceSettings', 'GET', '/api/v1/workspace-settings', (), (), (), '', False, 'WorkspaceSettings'),
    'updateWorkspaceSettings': Operation('updateWorkspaceSettings', 'PUT', '/api/v1/workspace-settings', (), (), ('Idempotency-Key',), 'WorkspaceSettingsRequest', True, 'WorkspaceSettings'),
}


class BrowserCloudGeneratedClient:
    def __init__(self, base_url: str, *, tenant_id: str, access_token: str | None = None, actor_id: str | None = None, timeout_seconds: float = 30.0, transport: Transport | None = None) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("base_url must be an absolute HTTP(S) URL")
        if not tenant_id:
            raise ValueError("tenant_id is required")
        self._base_url = base_url.rstrip("/")
        self._tenant_id = tenant_id
        self._access_token = access_token
        self._actor_id = actor_id
        self._timeout_seconds = timeout_seconds
        self._transport = transport or self._urllib_transport

    def call(self, operation_id: str, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        operation = OPERATIONS.get(operation_id)
        if operation is None:
            raise ValueError(f"unknown OpenAPI operation: {operation_id}")
        path_values = dict(path or {})
        route = operation.path
        for name in operation.path_parameters:
            if name not in path_values:
                raise ValueError(f"missing path parameter {name} for {operation_id}")
            route = route.replace("{" + name + "}", urllib.parse.quote(str(path_values[name]), safe=""))
        query_values = {key: value for key, value in (query or {}).items() if value is not None}
        unknown_query = set(query_values) - set(operation.query_parameters)
        if unknown_query:
            raise ValueError(f"unknown query parameters for {operation_id}: {sorted(unknown_query)}")
        if operation.request_required and body is None:
            raise ValueError(f"request body is required for {operation_id}")
        encoded_query = urllib.parse.urlencode(query_values, doseq=True)
        url = self._base_url + route + (("?" + encoded_query) if encoded_query else "")
        request_headers = {"Accept": "application/json", "Content-Type": "application/json"}
        controlled_headers = {"authorization", "x-tenant-id", "x-actor-id"}
        allowed_headers = {name.lower() for name in operation.header_parameters} - controlled_headers
        for name, value in (headers or {}).items():
            if name.lower() not in allowed_headers:
                raise ValueError(f"unknown or identity-controlled header {name} for {operation_id}")
            request_headers[name] = value
        if self._access_token:
            request_headers["Authorization"] = "Bearer " + self._access_token
        else:
            request_headers["X-Tenant-Id"] = self._tenant_id
            if self._actor_id:
                request_headers["X-Actor-Id"] = self._actor_id
        payload = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        status, response_headers, response_body = self._transport(operation.method, url, request_headers, payload)
        if status < 200 or status >= 300:
            parsed_error = json.loads(response_body.decode()) if response_body else {}
            raise ApiError(status, parsed_error.get("code", "UNKNOWN_ERROR"), parsed_error.get("message", f"HTTP {status}"), parsed_error.get("requestId"))
        if not response_body:
            return None
        content_type = next((value for key, value in response_headers.items() if key.lower() == "content-type"), "")
        if "json" in content_type or response_body[:1] in (b"{", b"["):
            return json.loads(response_body.decode())
        return response_body

    def _urllib_transport(self, method: str, url: str, headers: Mapping[str, str], body: bytes | None) -> tuple[int, Mapping[str, str], bytes]:
        request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()

    def getWorkspaceOverview(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getWorkspaceOverview', path=path, query=query, body=body, headers=headers)

    def streamWorkspaceOverviewChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamWorkspaceOverviewChanges', path=path, query=query, body=body, headers=headers)

    def getTenantCoordinatorRoute(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getTenantCoordinatorRoute', path=path, query=query, body=body, headers=headers)

    def getLatestTenantCoordinatorRouteMigration(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getLatestTenantCoordinatorRouteMigration', path=path, query=query, body=body, headers=headers)

    def requestTenantCoordinatorRouteMigration(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestTenantCoordinatorRouteMigration', path=path, query=query, body=body, headers=headers)

    def globalSearch(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('globalSearch', path=path, query=query, body=body, headers=headers)

    def listWorkspaceNotifications(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listWorkspaceNotifications', path=path, query=query, body=body, headers=headers)

    def streamWorkspaceNotificationChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamWorkspaceNotificationChanges', path=path, query=query, body=body, headers=headers)

    def updateWorkspaceNotificationReadCursor(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateWorkspaceNotificationReadCursor', path=path, query=query, body=body, headers=headers)

    def getUserPreferences(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getUserPreferences', path=path, query=query, body=body, headers=headers)

    def updateUserPreferences(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateUserPreferences', path=path, query=query, body=body, headers=headers)

    def listSessions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessions', path=path, query=query, body=body, headers=headers)

    def createSession(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createSession', path=path, query=query, body=body, headers=headers)

    def getSession(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getSession', path=path, query=query, body=body, headers=headers)

    def getBrowserState(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getBrowserState', path=path, query=query, body=body, headers=headers)

    def getSessionResources(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getSessionResources', path=path, query=query, body=body, headers=headers)

    def listSessionResourceEvents(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessionResourceEvents', path=path, query=query, body=body, headers=headers)

    def listSessionEvidence(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessionEvidence', path=path, query=query, body=body, headers=headers)

    def listSessionRecordings(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessionRecordings', path=path, query=query, body=body, headers=headers)

    def captureSessionEvidence(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('captureSessionEvidence', path=path, query=query, body=body, headers=headers)

    def getSessionEvidenceCapture(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getSessionEvidenceCapture', path=path, query=query, body=body, headers=headers)

    def createSessionEvidenceAccessGrant(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createSessionEvidenceAccessGrant', path=path, query=query, body=body, headers=headers)

    def redeemSessionEvidenceAccessGrant(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('redeemSessionEvidenceAccessGrant', path=path, query=query, body=body, headers=headers)

    def streamSessionResourceChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamSessionResourceChanges', path=path, query=query, body=body, headers=headers)

    def streamSessionChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamSessionChanges', path=path, query=query, body=body, headers=headers)

    def getSessionSafePoint(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getSessionSafePoint', path=path, query=query, body=body, headers=headers)

    def listSessionSafetyLeases(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessionSafetyLeases', path=path, query=query, body=body, headers=headers)

    def acquireSessionSafetyLease(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('acquireSessionSafetyLease', path=path, query=query, body=body, headers=headers)

    def renewSessionSafetyLease(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('renewSessionSafetyLease', path=path, query=query, body=body, headers=headers)

    def releaseSessionSafetyLease(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('releaseSessionSafetyLease', path=path, query=query, body=body, headers=headers)

    def getLatestSessionMigration(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getLatestSessionMigration', path=path, query=query, body=body, headers=headers)

    def rebindSessionProxy(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rebindSessionProxy', path=path, query=query, body=body, headers=headers)

    def getLatestSessionProxyRebind(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getLatestSessionProxyRebind', path=path, query=query, body=body, headers=headers)

    def getBusinessRecoveryValidation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getBusinessRecoveryValidation', path=path, query=query, body=body, headers=headers)

    def validateBusinessRecovery(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('validateBusinessRecovery', path=path, query=query, body=body, headers=headers)

    def listBusinessRecoveryProviderEvidence(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listBusinessRecoveryProviderEvidence', path=path, query=query, body=body, headers=headers)

    def submitBusinessRecoveryProviderEvidence(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('submitBusinessRecoveryProviderEvidence', path=path, query=query, body=body, headers=headers)

    def getSessionApplicationBinding(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getSessionApplicationBinding', path=path, query=query, body=body, headers=headers)

    def rebindSessionApplicationContract(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rebindSessionApplicationContract', path=path, query=query, body=body, headers=headers)

    def listApplicationRecoveryContracts(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listApplicationRecoveryContracts', path=path, query=query, body=body, headers=headers)

    def getApplicationRecoveryContract(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getApplicationRecoveryContract', path=path, query=query, body=body, headers=headers)

    def upsertApplicationRecoveryContract(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertApplicationRecoveryContract', path=path, query=query, body=body, headers=headers)

    def listApplicationRecoveryContractRevisions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listApplicationRecoveryContractRevisions', path=path, query=query, body=body, headers=headers)

    def diffApplicationRecoveryContractRevisions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('diffApplicationRecoveryContractRevisions', path=path, query=query, body=body, headers=headers)

    def restoreApplicationRecoveryContractRevision(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('restoreApplicationRecoveryContractRevision', path=path, query=query, body=body, headers=headers)

    def requestApplicationRecoveryContractApproval(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestApplicationRecoveryContractApproval', path=path, query=query, body=body, headers=headers)

    def approveApplicationRecoveryContract(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('approveApplicationRecoveryContract', path=path, query=query, body=body, headers=headers)

    def rejectApplicationRecoveryContract(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rejectApplicationRecoveryContract', path=path, query=query, body=body, headers=headers)

    def updateSessionResourcePolicy(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateSessionResourcePolicy', path=path, query=query, body=body, headers=headers)

    def startSession(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startSession', path=path, query=query, body=body, headers=headers)

    def resyncBrowserState(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('resyncBrowserState', path=path, query=query, body=body, headers=headers)

    def terminateSession(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('terminateSession', path=path, query=query, body=body, headers=headers)

    def requestHumanTakeover(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestHumanTakeover', path=path, query=query, body=body, headers=headers)

    def releaseHumanTakeover(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('releaseHumanTakeover', path=path, query=query, body=body, headers=headers)

    def createRemoteDesktopConnection(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createRemoteDesktopConnection', path=path, query=query, body=body, headers=headers)

    def listRemoteDesktopParticipants(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRemoteDesktopParticipants', path=path, query=query, body=body, headers=headers)

    def listRemoteDesktopParticipantHistory(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRemoteDesktopParticipantHistory', path=path, query=query, body=body, headers=headers)

    def revokeRemoteDesktopParticipant(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('revokeRemoteDesktopParticipant', path=path, query=query, body=body, headers=headers)

    def listProfiles(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listProfiles', path=path, query=query, body=body, headers=headers)

    def createProfile(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createProfile', path=path, query=query, body=body, headers=headers)

    def getProfile(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getProfile', path=path, query=query, body=body, headers=headers)

    def getProfileWarmTierStatus(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getProfileWarmTierStatus', path=path, query=query, body=body, headers=headers)

    def createProfileExportGrant(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createProfileExportGrant', path=path, query=query, body=body, headers=headers)

    def redeemProfileExportGrant(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('redeemProfileExportGrant', path=path, query=query, body=body, headers=headers)

    def listProfileImports(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listProfileImports', path=path, query=query, body=body, headers=headers)

    def importProfileCheckpoint(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('importProfileCheckpoint', path=path, query=query, body=body, headers=headers)

    def getProfileImport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getProfileImport', path=path, query=query, body=body, headers=headers)

    def getProxyOverview(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getProxyOverview', path=path, query=query, body=body, headers=headers)

    def listProxyBindings(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listProxyBindings', path=path, query=query, body=body, headers=headers)

    def createProxyBinding(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createProxyBinding', path=path, query=query, body=body, headers=headers)

    def updateProxyBinding(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateProxyBinding', path=path, query=query, body=body, headers=headers)

    def deleteProxyBinding(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('deleteProxyBinding', path=path, query=query, body=body, headers=headers)

    def createAgentTask(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createAgentTask', path=path, query=query, body=body, headers=headers)

    def listSessionChallenges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSessionChallenges', path=path, query=query, body=body, headers=headers)

    def getChallengeEvent(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getChallengeEvent', path=path, query=query, body=body, headers=headers)

    def previewHumanAssist(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('previewHumanAssist', path=path, query=query, body=body, headers=headers)

    def authorizeHumanAssist(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('authorizeHumanAssist', path=path, query=query, body=body, headers=headers)

    def listAgentTasks(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listAgentTasks', path=path, query=query, body=body, headers=headers)

    def listAgentTaskSummaries(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listAgentTaskSummaries', path=path, query=query, body=body, headers=headers)

    def getAgentTask(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getAgentTask', path=path, query=query, body=body, headers=headers)

    def executeAgentTask(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('executeAgentTask', path=path, query=query, body=body, headers=headers)

    def claimAgentExecutionJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('claimAgentExecutionJob', path=path, query=query, body=body, headers=headers)

    def startAgentExecutionJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startAgentExecutionJob', path=path, query=query, body=body, headers=headers)

    def heartbeatAgentExecutionJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('heartbeatAgentExecutionJob', path=path, query=query, body=body, headers=headers)

    def driveAgentExecutionJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('driveAgentExecutionJob', path=path, query=query, body=body, headers=headers)

    def failAgentExecutionJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('failAgentExecutionJob', path=path, query=query, body=body, headers=headers)

    def claimAgentReviewJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('claimAgentReviewJob', path=path, query=query, body=body, headers=headers)

    def startAgentReviewJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startAgentReviewJob', path=path, query=query, body=body, headers=headers)

    def heartbeatAgentReviewJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('heartbeatAgentReviewJob', path=path, query=query, body=body, headers=headers)

    def completeAgentReviewJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeAgentReviewJob', path=path, query=query, body=body, headers=headers)

    def failAgentReviewJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('failAgentReviewJob', path=path, query=query, body=body, headers=headers)

    def approveAgentTask(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('approveAgentTask', path=path, query=query, body=body, headers=headers)

    def rejectAgentTask(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rejectAgentTask', path=path, query=query, body=body, headers=headers)

    def acceptAgentHandoff(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('acceptAgentHandoff', path=path, query=query, body=body, headers=headers)

    def rejectAgentHandoff(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rejectAgentHandoff', path=path, query=query, body=body, headers=headers)

    def listAuditEvents(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listAuditEvents', path=path, query=query, body=body, headers=headers)

    def streamAuditEventChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamAuditEventChanges', path=path, query=query, body=body, headers=headers)

    def listRuntimeBuilds(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRuntimeBuilds', path=path, query=query, body=body, headers=headers)

    def requestRuntimePromotion(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestRuntimePromotion', path=path, query=query, body=body, headers=headers)

    def requestRuntimeDisable(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestRuntimeDisable', path=path, query=query, body=body, headers=headers)

    def listRuntimeReleaseRequests(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRuntimeReleaseRequests', path=path, query=query, body=body, headers=headers)

    def approveRuntimeRelease(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('approveRuntimeRelease', path=path, query=query, body=body, headers=headers)

    def rejectRuntimeRelease(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rejectRuntimeRelease', path=path, query=query, body=body, headers=headers)

    def listKeyRotationRequests(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listKeyRotationRequests', path=path, query=query, body=body, headers=headers)

    def requestKeyRotation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestKeyRotation', path=path, query=query, body=body, headers=headers)

    def approveKeyRotation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('approveKeyRotation', path=path, query=query, body=body, headers=headers)

    def completeKeyRotation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeKeyRotation', path=path, query=query, body=body, headers=headers)

    def revokeKeyRotation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('revokeKeyRotation', path=path, query=query, body=body, headers=headers)

    def listBreakGlassRequests(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listBreakGlassRequests', path=path, query=query, body=body, headers=headers)

    def requestBreakGlass(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('requestBreakGlass', path=path, query=query, body=body, headers=headers)

    def approveBreakGlass(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('approveBreakGlass', path=path, query=query, body=body, headers=headers)

    def rejectBreakGlass(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('rejectBreakGlass', path=path, query=query, body=body, headers=headers)

    def revokeBreakGlass(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('revokeBreakGlass', path=path, query=query, body=body, headers=headers)

    def reviewBreakGlass(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('reviewBreakGlass', path=path, query=query, body=body, headers=headers)

    def startSecureDebug(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startSecureDebug', path=path, query=query, body=body, headers=headers)

    def listSecureDebugSessions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSecureDebugSessions', path=path, query=query, body=body, headers=headers)

    def readSecureDebugSnapshot(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('readSecureDebugSnapshot', path=path, query=query, body=body, headers=headers)

    def endSecureDebug(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('endSecureDebug', path=path, query=query, body=body, headers=headers)

    def listBrowserNodes(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listBrowserNodes', path=path, query=query, body=body, headers=headers)

    def registerBrowserNode(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('registerBrowserNode', path=path, query=query, body=body, headers=headers)

    def reportBrowserNodePressure(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('reportBrowserNodePressure', path=path, query=query, body=body, headers=headers)

    def listExtensionProfiles(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listExtensionProfiles', path=path, query=query, body=body, headers=headers)

    def upsertExtensionProfile(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertExtensionProfile', path=path, query=query, body=body, headers=headers)

    def recordExtensionProfileSample(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('recordExtensionProfileSample', path=path, query=query, body=body, headers=headers)

    def getBrowserPlacement(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getBrowserPlacement', path=path, query=query, body=body, headers=headers)

    def getEnterpriseOverview(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getEnterpriseOverview', path=path, query=query, body=body, headers=headers)

    def streamEnterpriseOverviewChanges(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('streamEnterpriseOverviewChanges', path=path, query=query, body=body, headers=headers)

    def listRuntimeValidations(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRuntimeValidations', path=path, query=query, body=body, headers=headers)

    def startRuntimeValidation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startRuntimeValidation', path=path, query=query, body=body, headers=headers)

    def completeRuntimeValidation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeRuntimeValidation', path=path, query=query, body=body, headers=headers)

    def startRuntimeValidationMatrix(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startRuntimeValidationMatrix', path=path, query=query, body=body, headers=headers)

    def claimRuntimeValidationJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('claimRuntimeValidationJob', path=path, query=query, body=body, headers=headers)

    def startClaimedRuntimeValidationJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startClaimedRuntimeValidationJob', path=path, query=query, body=body, headers=headers)

    def heartbeatRuntimeValidationJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('heartbeatRuntimeValidationJob', path=path, query=query, body=body, headers=headers)

    def completeRuntimeValidationJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeRuntimeValidationJob', path=path, query=query, body=body, headers=headers)

    def failRuntimeValidationJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('failRuntimeValidationJob', path=path, query=query, body=body, headers=headers)

    def listEnterpriseCostRates(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listEnterpriseCostRates', path=path, query=query, body=body, headers=headers)

    def createEnterpriseCostRate(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createEnterpriseCostRate', path=path, query=query, body=body, headers=headers)

    def explainSessionCost(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('explainSessionCost', path=path, query=query, body=body, headers=headers)

    def getTenantMediaQuota(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getTenantMediaQuota', path=path, query=query, body=body, headers=headers)

    def upsertTenantMediaQuota(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertTenantMediaQuota', path=path, query=query, body=body, headers=headers)

    def upsertSloPolicy(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertSloPolicy', path=path, query=query, body=body, headers=headers)

    def getErrorBudget(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getErrorBudget', path=path, query=query, body=body, headers=headers)

    def getReleaseFreezeState(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getReleaseFreezeState', path=path, query=query, body=body, headers=headers)

    def recordServiceLevelEvent(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('recordServiceLevelEvent', path=path, query=query, body=body, headers=headers)

    def listSlaExclusions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listSlaExclusions', path=path, query=query, body=body, headers=headers)

    def upsertSlaExclusion(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertSlaExclusion', path=path, query=query, body=body, headers=headers)

    def listRetentionPolicies(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRetentionPolicies', path=path, query=query, body=body, headers=headers)

    def upsertRetentionPolicy(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertRetentionPolicy', path=path, query=query, body=body, headers=headers)

    def createRetentionDeletionReceipt(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createRetentionDeletionReceipt', path=path, query=query, body=body, headers=headers)

    def listLicenseInventory(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listLicenseInventory', path=path, query=query, body=body, headers=headers)

    def upsertLicenseInventory(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertLicenseInventory', path=path, query=query, body=body, headers=headers)

    def generateAuditExportManifest(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('generateAuditExportManifest', path=path, query=query, body=body, headers=headers)

    def listEnterpriseRegions(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listEnterpriseRegions', path=path, query=query, body=body, headers=headers)

    def upsertEnterpriseRegion(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('upsertEnterpriseRegion', path=path, query=query, body=body, headers=headers)

    def listRecoveryGameDays(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRecoveryGameDays', path=path, query=query, body=body, headers=headers)

    def startRecoveryGameDay(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startRecoveryGameDay', path=path, query=query, body=body, headers=headers)

    def completeRecoveryGameDay(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeRecoveryGameDay', path=path, query=query, body=body, headers=headers)

    def getRecoveryGameDay(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getRecoveryGameDay', path=path, query=query, body=body, headers=headers)

    def listRecoveryGameDayEvents(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRecoveryGameDayEvents', path=path, query=query, body=body, headers=headers)

    def listRecoveryGameDayTrends(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRecoveryGameDayTrends', path=path, query=query, body=body, headers=headers)

    def generateRecoveryGameDayReport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('generateRecoveryGameDayReport', path=path, query=query, body=body, headers=headers)

    def getRecoveryGameDayReport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getRecoveryGameDayReport', path=path, query=query, body=body, headers=headers)

    def listRecoveryGameDayRemediations(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listRecoveryGameDayRemediations', path=path, query=query, body=body, headers=headers)

    def updateRecoveryGameDayRemediation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateRecoveryGameDayRemediation', path=path, query=query, body=body, headers=headers)

    def abortRecoveryGameDay(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('abortRecoveryGameDay', path=path, query=query, body=body, headers=headers)

    def claimRecoveryGameDayJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('claimRecoveryGameDayJob', path=path, query=query, body=body, headers=headers)

    def startRecoveryGameDayJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('startRecoveryGameDayJob', path=path, query=query, body=body, headers=headers)

    def heartbeatRecoveryGameDayJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('heartbeatRecoveryGameDayJob', path=path, query=query, body=body, headers=headers)

    def updateRecoveryGameDayJobStage(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateRecoveryGameDayJobStage', path=path, query=query, body=body, headers=headers)

    def completeRecoveryGameDayJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('completeRecoveryGameDayJob', path=path, query=query, body=body, headers=headers)

    def failRecoveryGameDayJob(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('failRecoveryGameDayJob', path=path, query=query, body=body, headers=headers)

    def generateComplianceSnapshot(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('generateComplianceSnapshot', path=path, query=query, body=body, headers=headers)

    def listEnvironmentSavedViews(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listEnvironmentSavedViews', path=path, query=query, body=body, headers=headers)

    def createEnvironmentSavedView(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createEnvironmentSavedView', path=path, query=query, body=body, headers=headers)

    def updateEnvironmentSavedView(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateEnvironmentSavedView', path=path, query=query, body=body, headers=headers)

    def deleteEnvironmentSavedView(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('deleteEnvironmentSavedView', path=path, query=query, body=body, headers=headers)

    def listEnvironmentImports(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listEnvironmentImports', path=path, query=query, body=body, headers=headers)

    def previewEnvironmentImport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('previewEnvironmentImport', path=path, query=query, body=body, headers=headers)

    def getEnvironmentImport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getEnvironmentImport', path=path, query=query, body=body, headers=headers)

    def commitEnvironmentImport(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('commitEnvironmentImport', path=path, query=query, body=body, headers=headers)

    def listWorkspaceGroups(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listWorkspaceGroups', path=path, query=query, body=body, headers=headers)

    def createWorkspaceGroup(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createWorkspaceGroup', path=path, query=query, body=body, headers=headers)

    def updateWorkspaceGroup(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateWorkspaceGroup', path=path, query=query, body=body, headers=headers)

    def deleteWorkspaceGroup(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('deleteWorkspaceGroup', path=path, query=query, body=body, headers=headers)

    def assignSessionToWorkspaceGroup(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('assignSessionToWorkspaceGroup', path=path, query=query, body=body, headers=headers)

    def unassignSessionFromWorkspaceGroup(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('unassignSessionFromWorkspaceGroup', path=path, query=query, body=body, headers=headers)

    def listWorkspaceTags(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listWorkspaceTags', path=path, query=query, body=body, headers=headers)

    def createWorkspaceTag(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createWorkspaceTag', path=path, query=query, body=body, headers=headers)

    def updateWorkspaceTag(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateWorkspaceTag', path=path, query=query, body=body, headers=headers)

    def deleteWorkspaceTag(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('deleteWorkspaceTag', path=path, query=query, body=body, headers=headers)

    def assignSessionToWorkspaceTag(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('assignSessionToWorkspaceTag', path=path, query=query, body=body, headers=headers)

    def unassignSessionFromWorkspaceTag(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('unassignSessionFromWorkspaceTag', path=path, query=query, body=body, headers=headers)

    def listWorkspaceBatchOperations(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listWorkspaceBatchOperations', path=path, query=query, body=body, headers=headers)

    def createWorkspaceBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createWorkspaceBatchOperation', path=path, query=query, body=body, headers=headers)

    def getWorkspaceBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getWorkspaceBatchOperation', path=path, query=query, body=body, headers=headers)

    def cancelWorkspaceBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('cancelWorkspaceBatchOperation', path=path, query=query, body=body, headers=headers)

    def listWorkspaceMetadataBatchOperations(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('listWorkspaceMetadataBatchOperations', path=path, query=query, body=body, headers=headers)

    def createWorkspaceMetadataBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('createWorkspaceMetadataBatchOperation', path=path, query=query, body=body, headers=headers)

    def getWorkspaceMetadataBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getWorkspaceMetadataBatchOperation', path=path, query=query, body=body, headers=headers)

    def cancelWorkspaceMetadataBatchOperation(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('cancelWorkspaceMetadataBatchOperation', path=path, query=query, body=body, headers=headers)

    def getWorkspaceSettings(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('getWorkspaceSettings', path=path, query=query, body=body, headers=headers)

    def updateWorkspaceSettings(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        return self.call('updateWorkspaceSettings', path=path, query=query, body=body, headers=headers)


__all__ = ["ApiError", "BrowserCloudGeneratedClient", "Operation", "OPERATIONS"]
