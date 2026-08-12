// Code generated from session-api.yaml; DO NOT EDIT.

package generated

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const Generator = "browsercloud-multilang-generator@1"

type Operation struct {
	OperationID      string
	Method           string
	Path             string
	PathParameters   []string
	QueryParameters  []string
	HeaderParameters []string
	RequestSchema    string
	RequestRequired  bool
	ResponseSchema   string
}

type Request struct {
	Path    map[string]string
	Query   url.Values
	Headers http.Header
	Body    any
}

type Options struct {
	BaseURL, TenantID, AccessToken, ActorID string
	HTTPClient                              *http.Client
}
type Client struct {
	baseURL, tenantID, accessToken, actorID string
	httpClient                              *http.Client
}
type APIError struct {
	Status                   int
	Code, Message, RequestID string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("%d %s: %s request_id=%s", e.Status, e.Code, e.Message, e.RequestID)
}

var Operations = map[string]Operation{
	"getWorkspaceOverview":                       {OperationID: "getWorkspaceOverview", Method: "GET", Path: "/api/v1/workspace-overview", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceOverview"},
	"streamWorkspaceOverviewChanges":             {OperationID: "streamWorkspaceOverviewChanges", Method: "GET", Path: "/api/v1/workspace-overview/event-stream", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Last-Event-ID"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "string"},
	"getTenantCoordinatorRoute":                  {OperationID: "getTenantCoordinatorRoute", Method: "GET", Path: "/api/v1/coordinator/tenant-route", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "TenantRoute"},
	"getLatestTenantCoordinatorRouteMigration":   {OperationID: "getLatestTenantCoordinatorRouteMigration", Method: "GET", Path: "/api/v1/coordinator/tenant-route/migration", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "TenantRouteMigration"},
	"requestTenantCoordinatorRouteMigration":     {OperationID: "requestTenantCoordinatorRouteMigration", Method: "POST", Path: "/api/v1/coordinator/tenant-route/migrations", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "RequestTenantRouteMigration", RequestRequired: true, ResponseSchema: "TenantRouteMigration"},
	"globalSearch":                               {OperationID: "globalSearch", Method: "GET", Path: "/api/v1/search", PathParameters: nil, QueryParameters: []string{"limit", "q", "types"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "GlobalSearchResponse"},
	"listWorkspaceNotifications":                 {OperationID: "listWorkspaceNotifications", Method: "GET", Path: "/api/v1/notifications", PathParameters: nil, QueryParameters: []string{"beforeSequence", "limit"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceNotificationListResponse"},
	"streamWorkspaceNotificationChanges":         {OperationID: "streamWorkspaceNotificationChanges", Method: "GET", Path: "/api/v1/notifications/event-stream", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Last-Event-ID"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "string"},
	"updateWorkspaceNotificationReadCursor":      {OperationID: "updateWorkspaceNotificationReadCursor", Method: "PATCH", Path: "/api/v1/notifications/read-cursor", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpdateNotificationReadCursorRequest", RequestRequired: true, ResponseSchema: "WorkspaceNotificationReadState"},
	"getUserPreferences":                         {OperationID: "getUserPreferences", Method: "GET", Path: "/api/v1/user-preferences", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "UserPreferences"},
	"updateUserPreferences":                      {OperationID: "updateUserPreferences", Method: "PUT", Path: "/api/v1/user-preferences", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpdateUserPreferencesRequest", RequestRequired: true, ResponseSchema: "UserPreferences"},
	"listSessions":                               {OperationID: "listSessions", Method: "GET", Path: "/api/v1/sessions", PathParameters: nil, QueryParameters: []string{"groupId", "limit", "offset", "q", "state", "tagId", "tagMatch"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionListResponse"},
	"createSession":                              {OperationID: "createSession", Method: "POST", Path: "/api/v1/sessions", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "CreateSessionRequest", RequestRequired: true, ResponseSchema: "CreateSessionResponse"},
	"getSession":                                 {OperationID: "getSession", Method: "GET", Path: "/api/v1/sessions/{sessionId}", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionView"},
	"getBrowserState":                            {OperationID: "getBrowserState", Method: "GET", Path: "/api/v1/sessions/{sessionId}/state", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BrowserState"},
	"getSessionResources":                        {OperationID: "getSessionResources", Method: "GET", Path: "/api/v1/sessions/{sessionId}/resources", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionResource"},
	"listSessionResourceEvents":                  {OperationID: "listSessionResourceEvents", Method: "GET", Path: "/api/v1/sessions/{sessionId}/resource-events", PathParameters: []string{"sessionId"}, QueryParameters: []string{"limit", "offset"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ResourceEventList"},
	"listSessionEvidence":                        {OperationID: "listSessionEvidence", Method: "GET", Path: "/api/v1/sessions/{sessionId}/evidence", PathParameters: []string{"sessionId"}, QueryParameters: []string{"limit", "offset"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "EvidenceList"},
	"captureSessionEvidence":                     {OperationID: "captureSessionEvidence", Method: "POST", Path: "/api/v1/sessions/{sessionId}/evidence:capture", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "CaptureEvidenceRequest", RequestRequired: true, ResponseSchema: "EvidenceCapture"},
	"getSessionEvidenceCapture":                  {OperationID: "getSessionEvidenceCapture", Method: "GET", Path: "/api/v1/sessions/{sessionId}/evidence-captures/{captureId}", PathParameters: []string{"captureId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "EvidenceCapture"},
	"createSessionEvidenceAccessGrant":           {OperationID: "createSessionEvidenceAccessGrant", Method: "POST", Path: "/api/v1/sessions/{sessionId}/evidence/{evidenceId}/access-grants", PathParameters: []string{"evidenceId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "CreateEvidenceAccessGrantRequest", RequestRequired: true, ResponseSchema: "EvidenceAccessGrant"},
	"redeemSessionEvidenceAccessGrant":           {OperationID: "redeemSessionEvidenceAccessGrant", Method: "POST", Path: "/api/v1/sessions/{sessionId}/evidence-access-grants/{grantId}:redeem", PathParameters: []string{"grantId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RedeemEvidenceAccessResponse"},
	"streamSessionResourceChanges":               {OperationID: "streamSessionResourceChanges", Method: "GET", Path: "/api/v1/sessions/{sessionId}/resource-stream", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Last-Event-ID", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "string"},
	"streamSessionChanges":                       {OperationID: "streamSessionChanges", Method: "GET", Path: "/api/v1/sessions/{sessionId}/event-stream", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Last-Event-ID", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "string"},
	"getSessionSafePoint":                        {OperationID: "getSessionSafePoint", Method: "GET", Path: "/api/v1/sessions/{sessionId}/safe-point", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionSafePoint"},
	"listSessionSafetyLeases":                    {OperationID: "listSessionSafetyLeases", Method: "GET", Path: "/api/v1/sessions/{sessionId}/safety-leases", PathParameters: []string{"sessionId"}, QueryParameters: []string{"limit"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SafetyLeaseList"},
	"acquireSessionSafetyLease":                  {OperationID: "acquireSessionSafetyLease", Method: "POST", Path: "/api/v1/sessions/{sessionId}/safety-leases", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "CreateSafetyLeaseRequest", RequestRequired: true, ResponseSchema: "SafetyLease"},
	"renewSessionSafetyLease":                    {OperationID: "renewSessionSafetyLease", Method: "PUT", Path: "/api/v1/sessions/{sessionId}/safety-leases/{leaseId}", PathParameters: []string{"leaseId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "RenewSafetyLeaseRequest", RequestRequired: true, ResponseSchema: "SafetyLease"},
	"releaseSessionSafetyLease":                  {OperationID: "releaseSessionSafetyLease", Method: "POST", Path: "/api/v1/sessions/{sessionId}/safety-leases/{leaseId}:release", PathParameters: []string{"leaseId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SafetyLease"},
	"getLatestSessionMigration":                  {OperationID: "getLatestSessionMigration", Method: "GET", Path: "/api/v1/sessions/{sessionId}/migration", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionMigration"},
	"rebindSessionProxy":                         {OperationID: "rebindSessionProxy", Method: "POST", Path: "/api/v1/sessions/{sessionId}/proxy-binding:rebind", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "ProxyRebindRequest", RequestRequired: true, ResponseSchema: "ProxyRebindOperation"},
	"getLatestSessionProxyRebind":                {OperationID: "getLatestSessionProxyRebind", Method: "GET", Path: "/api/v1/sessions/{sessionId}/proxy-rebind", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProxyRebind"},
	"getBusinessRecoveryValidation":              {OperationID: "getBusinessRecoveryValidation", Method: "GET", Path: "/api/v1/sessions/{sessionId}/business-recovery", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BusinessRecoveryValidation"},
	"validateBusinessRecovery":                   {OperationID: "validateBusinessRecovery", Method: "POST", Path: "/api/v1/sessions/{sessionId}/business-recovery:validate", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BusinessRecoveryValidation"},
	"listBusinessRecoveryProviderEvidence":       {OperationID: "listBusinessRecoveryProviderEvidence", Method: "GET", Path: "/api/v1/sessions/{sessionId}/business-recovery/provider-evidence", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProviderEvidenceListResponse"},
	"submitBusinessRecoveryProviderEvidence":     {OperationID: "submitBusinessRecoveryProviderEvidence", Method: "POST", Path: "/api/v1/sessions/{sessionId}/business-recovery/provider-evidence", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "SubmitProviderEvidenceRequest", RequestRequired: true, ResponseSchema: "ProviderEvidence"},
	"getSessionApplicationBinding":               {OperationID: "getSessionApplicationBinding", Method: "GET", Path: "/api/v1/sessions/{sessionId}/application-binding", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionApplicationBinding"},
	"rebindSessionApplicationContract":           {OperationID: "rebindSessionApplicationContract", Method: "POST", Path: "/api/v1/sessions/{sessionId}/application-binding:rebind", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "RebindSessionApplicationRequest", RequestRequired: true, ResponseSchema: "SessionApplicationRebind"},
	"listApplicationRecoveryContracts":           {OperationID: "listApplicationRecoveryContracts", Method: "GET", Path: "/api/v1/applications/recovery-contracts", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContractListResponse"},
	"getApplicationRecoveryContract":             {OperationID: "getApplicationRecoveryContract", Method: "GET", Path: "/api/v1/applications/{applicationId}/recovery-contract", PathParameters: []string{"applicationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContract"},
	"upsertApplicationRecoveryContract":          {OperationID: "upsertApplicationRecoveryContract", Method: "PUT", Path: "/api/v1/applications/{applicationId}/recovery-contract", PathParameters: []string{"applicationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "UpsertRecoveryContractRequest", RequestRequired: true, ResponseSchema: "RecoveryContract"},
	"listApplicationRecoveryContractRevisions":   {OperationID: "listApplicationRecoveryContractRevisions", Method: "GET", Path: "/api/v1/applications/{applicationId}/recovery-contract/revisions", PathParameters: []string{"applicationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContractRevisionListResponse"},
	"diffApplicationRecoveryContractRevisions":   {OperationID: "diffApplicationRecoveryContractRevisions", Method: "GET", Path: "/api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff", PathParameters: []string{"applicationId", "version"}, QueryParameters: []string{"compareToVersion"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContractDiff"},
	"restoreApplicationRecoveryContractRevision": {OperationID: "restoreApplicationRecoveryContractRevision", Method: "POST", Path: "/api/v1/applications/{applicationId}/recovery-contract:restore", PathParameters: []string{"applicationId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "RestoreRecoveryContractRevisionRequest", RequestRequired: true, ResponseSchema: "RecoveryContract"},
	"requestApplicationRecoveryContractApproval": {OperationID: "requestApplicationRecoveryContractApproval", Method: "POST", Path: "/api/v1/applications/{applicationId}/recovery-contract:request-approval", PathParameters: []string{"applicationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "RequestRecoveryContractApprovalRequest", RequestRequired: true, ResponseSchema: "RecoveryContractApproval"},
	"approveApplicationRecoveryContract":         {OperationID: "approveApplicationRecoveryContract", Method: "POST", Path: "/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve", PathParameters: []string{"applicationId", "approvalId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContractApproval"},
	"rejectApplicationRecoveryContract":          {OperationID: "rejectApplicationRecoveryContract", Method: "POST", Path: "/api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject", PathParameters: []string{"applicationId", "approvalId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryContractApproval"},
	"updateSessionResourcePolicy":                {OperationID: "updateSessionResourcePolicy", Method: "PATCH", Path: "/api/v1/sessions/{sessionId}/resource-policy", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "ResourcePolicyRequest", RequestRequired: true, ResponseSchema: "ResourcePolicyOperation"},
	"startSession":                               {OperationID: "startSession", Method: "POST", Path: "/api/v1/sessions/{sessionId}:start", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "OperationResponse"},
	"resyncBrowserState":                         {OperationID: "resyncBrowserState", Method: "POST", Path: "/api/v1/sessions/{sessionId}:resync-state", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "StateResyncRequest", RequestRequired: true, ResponseSchema: "StateResyncResponse"},
	"terminateSession":                           {OperationID: "terminateSession", Method: "POST", Path: "/api/v1/sessions/{sessionId}:terminate", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "OperationResponse"},
	"requestHumanTakeover":                       {OperationID: "requestHumanTakeover", Method: "POST", Path: "/api/v1/sessions/{sessionId}:takeover", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "OperationResponse"},
	"releaseHumanTakeover":                       {OperationID: "releaseHumanTakeover", Method: "POST", Path: "/api/v1/sessions/{sessionId}:release-takeover", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "OperationResponse"},
	"createRemoteDesktopConnection":              {OperationID: "createRemoteDesktopConnection", Method: "POST", Path: "/api/v1/sessions/{sessionId}:desktop-connection", PathParameters: []string{"sessionId"}, QueryParameters: []string{"viewOnly"}, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RemoteDesktopConnection"},
	"listRemoteDesktopParticipants":              {OperationID: "listRemoteDesktopParticipants", Method: "GET", Path: "/api/v1/sessions/{sessionId}/desktop-participants", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RemoteDesktopParticipantList"},
	"revokeRemoteDesktopParticipant":             {OperationID: "revokeRemoteDesktopParticipant", Method: "POST", Path: "/api/v1/sessions/{sessionId}/desktop-participants/{connectionId}:revoke", PathParameters: []string{"connectionId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RemoteDesktopParticipant"},
	"listProfiles":                               {OperationID: "listProfiles", Method: "GET", Path: "/api/v1/profiles", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProfileListResponse"},
	"createProfile":                              {OperationID: "createProfile", Method: "POST", Path: "/api/v1/profiles", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "CreateProfileRequest", RequestRequired: true, ResponseSchema: "Profile"},
	"getProfile":                                 {OperationID: "getProfile", Method: "GET", Path: "/api/v1/profiles/{profileId}", PathParameters: []string{"profileId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "Profile"},
	"listProfileImports":                         {OperationID: "listProfileImports", Method: "GET", Path: "/api/v1/profile-imports", PathParameters: nil, QueryParameters: []string{"limit"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProfileImportListResponse"},
	"importProfileCheckpoint":                    {OperationID: "importProfileCheckpoint", Method: "POST", Path: "/api/v1/profile-imports", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "object", RequestRequired: true, ResponseSchema: "ProfileImport"},
	"getProfileImport":                           {OperationID: "getProfileImport", Method: "GET", Path: "/api/v1/profile-imports/{importId}", PathParameters: []string{"importId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProfileImport"},
	"getProxyOverview":                           {OperationID: "getProxyOverview", Method: "GET", Path: "/api/v1/proxies", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProxyOverview"},
	"listProxyBindings":                          {OperationID: "listProxyBindings", Method: "GET", Path: "/api/v1/proxy-bindings", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "ProxyBindingList"},
	"createProxyBinding":                         {OperationID: "createProxyBinding", Method: "POST", Path: "/api/v1/proxy-bindings", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "ProxyBindingRequest", RequestRequired: true, ResponseSchema: "ProxyBinding"},
	"updateProxyBinding":                         {OperationID: "updateProxyBinding", Method: "PUT", Path: "/api/v1/proxy-bindings/{bindingProfileId}", PathParameters: []string{"bindingProfileId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "ProxyBindingRequest", RequestRequired: true, ResponseSchema: "ProxyBinding"},
	"deleteProxyBinding":                         {OperationID: "deleteProxyBinding", Method: "DELETE", Path: "/api/v1/proxy-bindings/{bindingProfileId}", PathParameters: []string{"bindingProfileId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: ""},
	"createAgentTask":                            {OperationID: "createAgentTask", Method: "POST", Path: "/api/v1/sessions/{sessionId}/agent-tasks", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "CreateAgentTaskRequest", RequestRequired: true, ResponseSchema: "AgentTask"},
	"listSessionChallenges":                      {OperationID: "listSessionChallenges", Method: "GET", Path: "/api/v1/sessions/{sessionId}/challenges", PathParameters: []string{"sessionId"}, QueryParameters: []string{"limit"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ChallengeEventListResponse"},
	"getChallengeEvent":                          {OperationID: "getChallengeEvent", Method: "GET", Path: "/api/v1/challenges/{eventId}", PathParameters: []string{"eventId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ChallengeEvent"},
	"previewHumanAssist":                         {OperationID: "previewHumanAssist", Method: "GET", Path: "/api/v1/challenges/{eventId}/preview", PathParameters: []string{"eventId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ChallengePreview"},
	"authorizeHumanAssist":                       {OperationID: "authorizeHumanAssist", Method: "POST", Path: "/api/v1/challenges/{eventId}/assist-authorizations", PathParameters: []string{"eventId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "AuthorizeHumanAssistRequest", RequestRequired: true, ResponseSchema: "HumanAssistIntent"},
	"listAgentTasks":                             {OperationID: "listAgentTasks", Method: "GET", Path: "/api/v1/agent-tasks", PathParameters: nil, QueryParameters: []string{"limit", "offset"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTaskListResponse"},
	"listAgentTaskSummaries":                     {OperationID: "listAgentTaskSummaries", Method: "GET", Path: "/api/v1/agent-task-summaries", PathParameters: nil, QueryParameters: []string{"cursor", "limit"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTaskSummaryListResponse"},
	"getAgentTask":                               {OperationID: "getAgentTask", Method: "GET", Path: "/api/v1/agent-tasks/{taskId}", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"executeAgentTask":                           {OperationID: "executeAgentTask", Method: "POST", Path: "/api/v1/agent-tasks/{taskId}:execute", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"claimAgentExecutionJob":                     {OperationID: "claimAgentExecutionJob", Method: "POST", Path: "/api/v1/agent-worker-jobs:claim", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "ClaimAgentExecutionJobRequest", RequestRequired: true, ResponseSchema: "AgentExecutionJobClaim"},
	"startAgentExecutionJob":                     {OperationID: "startAgentExecutionJob", Method: "POST", Path: "/api/v1/agent-worker-jobs/{jobId}:start", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "AgentExecutionJobClaimRequest", RequestRequired: true, ResponseSchema: "AgentExecutionJob"},
	"heartbeatAgentExecutionJob":                 {OperationID: "heartbeatAgentExecutionJob", Method: "POST", Path: "/api/v1/agent-worker-jobs/{jobId}:heartbeat", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "AgentExecutionJobClaimRequest", RequestRequired: true, ResponseSchema: "AgentExecutionJob"},
	"driveAgentExecutionJob":                     {OperationID: "driveAgentExecutionJob", Method: "POST", Path: "/api/v1/agent-worker-jobs/{jobId}:drive", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "AgentExecutionJobClaimRequest", RequestRequired: true, ResponseSchema: "AgentExecutionJob"},
	"failAgentExecutionJob":                      {OperationID: "failAgentExecutionJob", Method: "POST", Path: "/api/v1/agent-worker-jobs/{jobId}:fail", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "FailAgentExecutionJobRequest", RequestRequired: true, ResponseSchema: "AgentExecutionJob"},
	"claimAgentReviewJob":                        {OperationID: "claimAgentReviewJob", Method: "POST", Path: "/api/v1/agent-review-jobs:claim", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "ClaimAgentReviewJobRequest", RequestRequired: true, ResponseSchema: "AgentReviewJobClaim"},
	"startAgentReviewJob":                        {OperationID: "startAgentReviewJob", Method: "POST", Path: "/api/v1/agent-review-jobs/{jobId}:start", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "AgentReviewJobClaimRequest", RequestRequired: true, ResponseSchema: "AgentReviewJob"},
	"heartbeatAgentReviewJob":                    {OperationID: "heartbeatAgentReviewJob", Method: "POST", Path: "/api/v1/agent-review-jobs/{jobId}:heartbeat", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "AgentReviewJobClaimRequest", RequestRequired: true, ResponseSchema: "AgentReviewJob"},
	"completeAgentReviewJob":                     {OperationID: "completeAgentReviewJob", Method: "POST", Path: "/api/v1/agent-review-jobs/{jobId}:complete", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CompleteAgentReviewJobRequest", RequestRequired: true, ResponseSchema: "AgentReviewJob"},
	"failAgentReviewJob":                         {OperationID: "failAgentReviewJob", Method: "POST", Path: "/api/v1/agent-review-jobs/{jobId}:fail", PathParameters: []string{"jobId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "FailAgentReviewJobRequest", RequestRequired: true, ResponseSchema: "AgentReviewJob"},
	"approveAgentTask":                           {OperationID: "approveAgentTask", Method: "POST", Path: "/api/v1/agent-tasks/{taskId}:approve", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"rejectAgentTask":                            {OperationID: "rejectAgentTask", Method: "POST", Path: "/api/v1/agent-tasks/{taskId}:reject", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"acceptAgentHandoff":                         {OperationID: "acceptAgentHandoff", Method: "POST", Path: "/api/v1/agent-tasks/{taskId}:accept-handoff", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"rejectAgentHandoff":                         {OperationID: "rejectAgentHandoff", Method: "POST", Path: "/api/v1/agent-tasks/{taskId}:reject-handoff", PathParameters: []string{"taskId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AgentTask"},
	"listAuditEvents":                            {OperationID: "listAuditEvents", Method: "GET", Path: "/api/v1/audit-events", PathParameters: nil, QueryParameters: []string{"eventType", "limit", "offset", "sessionId"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "AuditEventListResponse"},
	"listRuntimeBuilds":                          {OperationID: "listRuntimeBuilds", Method: "GET", Path: "/api/v1/runtime-builds", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RuntimeBuildListResponse"},
	"requestRuntimePromotion":                    {OperationID: "requestRuntimePromotion", Method: "POST", Path: "/api/v1/runtime-builds/{buildId}:promote", PathParameters: []string{"buildId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "CreateRuntimeReleaseRequest", RequestRequired: true, ResponseSchema: "RuntimeReleaseRequest"},
	"requestRuntimeDisable":                      {OperationID: "requestRuntimeDisable", Method: "POST", Path: "/api/v1/runtime-builds/{buildId}:disable", PathParameters: []string{"buildId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "CreateRuntimeDisableRequest", RequestRequired: true, ResponseSchema: "RuntimeReleaseRequest"},
	"listRuntimeReleaseRequests":                 {OperationID: "listRuntimeReleaseRequests", Method: "GET", Path: "/api/v1/runtime-release-requests", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RuntimeReleaseRequestListResponse"},
	"approveRuntimeRelease":                      {OperationID: "approveRuntimeRelease", Method: "POST", Path: "/api/v1/runtime-release-requests/{releaseId}:approve", PathParameters: []string{"releaseId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RuntimeReleaseRequest"},
	"rejectRuntimeRelease":                       {OperationID: "rejectRuntimeRelease", Method: "POST", Path: "/api/v1/runtime-release-requests/{releaseId}:reject", PathParameters: []string{"releaseId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "RuntimeReleaseRequest"},
	"listKeyRotationRequests":                    {OperationID: "listKeyRotationRequests", Method: "GET", Path: "/api/v1/key-rotation-requests", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "KeyRotationRequestListResponse"},
	"requestKeyRotation":                         {OperationID: "requestKeyRotation", Method: "POST", Path: "/api/v1/key-rotation-requests", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "CreateKeyRotationRequest", RequestRequired: true, ResponseSchema: "KeyRotationRequest"},
	"approveKeyRotation":                         {OperationID: "approveKeyRotation", Method: "POST", Path: "/api/v1/key-rotation-requests/{rotationId}:approve", PathParameters: []string{"rotationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "KeyRotationRequest"},
	"completeKeyRotation":                        {OperationID: "completeKeyRotation", Method: "POST", Path: "/api/v1/key-rotation-requests/{rotationId}:complete", PathParameters: []string{"rotationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "CompleteKeyRotationRequest", RequestRequired: true, ResponseSchema: "KeyRotationRequest"},
	"revokeKeyRotation":                          {OperationID: "revokeKeyRotation", Method: "POST", Path: "/api/v1/key-rotation-requests/{rotationId}:revoke", PathParameters: []string{"rotationId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "KeyRotationRequest"},
	"listBreakGlassRequests":                     {OperationID: "listBreakGlassRequests", Method: "GET", Path: "/api/v1/break-glass-requests", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BreakGlassRequestListResponse"},
	"requestBreakGlass":                          {OperationID: "requestBreakGlass", Method: "POST", Path: "/api/v1/break-glass-requests", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "CreateBreakGlassRequest", RequestRequired: true, ResponseSchema: "BreakGlassRequest"},
	"approveBreakGlass":                          {OperationID: "approveBreakGlass", Method: "POST", Path: "/api/v1/break-glass-requests/{requestId}:approve", PathParameters: []string{"requestId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BreakGlassRequest"},
	"rejectBreakGlass":                           {OperationID: "rejectBreakGlass", Method: "POST", Path: "/api/v1/break-glass-requests/{requestId}:reject", PathParameters: []string{"requestId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BreakGlassRequest"},
	"revokeBreakGlass":                           {OperationID: "revokeBreakGlass", Method: "POST", Path: "/api/v1/break-glass-requests/{requestId}:revoke", PathParameters: []string{"requestId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BreakGlassRequest"},
	"reviewBreakGlass":                           {OperationID: "reviewBreakGlass", Method: "POST", Path: "/api/v1/break-glass-requests/{requestId}:review", PathParameters: []string{"requestId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "BreakGlassRequest"},
	"startSecureDebug":                           {OperationID: "startSecureDebug", Method: "POST", Path: "/api/v1/break-glass-requests/{requestId}:start-secure-debug", PathParameters: []string{"requestId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SecureDebugSession"},
	"listSecureDebugSessions":                    {OperationID: "listSecureDebugSessions", Method: "GET", Path: "/api/v1/secure-debug-sessions", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SecureDebugSessionListResponse"},
	"readSecureDebugSnapshot":                    {OperationID: "readSecureDebugSnapshot", Method: "GET", Path: "/api/v1/secure-debug-sessions/{debugSessionId}/snapshot", PathParameters: []string{"debugSessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SecureDebugSnapshot"},
	"endSecureDebug":                             {OperationID: "endSecureDebug", Method: "POST", Path: "/api/v1/secure-debug-sessions/{debugSessionId}:end", PathParameters: []string{"debugSessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Actor-Id", "X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SecureDebugSession"},
	"listBrowserNodes":                           {OperationID: "listBrowserNodes", Method: "GET", Path: "/api/v1/browser-nodes", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "BrowserNodeListResponse"},
	"registerBrowserNode":                        {OperationID: "registerBrowserNode", Method: "PUT", Path: "/api/v1/browser-nodes/{nodeId}", PathParameters: []string{"nodeId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RegisterBrowserNodeRequest", RequestRequired: true, ResponseSchema: "BrowserNode"},
	"reportBrowserNodePressure":                  {OperationID: "reportBrowserNodePressure", Method: "POST", Path: "/api/v1/browser-nodes/{nodeId}:pressure", PathParameters: []string{"nodeId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RecordNodePressureRequest", RequestRequired: true, ResponseSchema: "BrowserNode"},
	"listExtensionProfiles":                      {OperationID: "listExtensionProfiles", Method: "GET", Path: "/api/v1/extensions", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "ExtensionProfileListResponse"},
	"upsertExtensionProfile":                     {OperationID: "upsertExtensionProfile", Method: "PUT", Path: "/api/v1/extensions/{extensionId}", PathParameters: []string{"extensionId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpsertExtensionProfileRequest", RequestRequired: true, ResponseSchema: "ExtensionProfile"},
	"recordExtensionProfileSample":               {OperationID: "recordExtensionProfileSample", Method: "POST", Path: "/api/v1/extensions/{extensionId}:sample", PathParameters: []string{"extensionId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RecordExtensionSampleRequest", RequestRequired: true, ResponseSchema: "ExtensionProfile"},
	"getBrowserPlacement":                        {OperationID: "getBrowserPlacement", Method: "GET", Path: "/api/v1/browser-placements/{sessionId}", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "BrowserPlacement"},
	"getEnterpriseOverview":                      {OperationID: "getEnterpriseOverview", Method: "GET", Path: "/api/v1/enterprise/overview", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "EnterpriseOverview"},
	"listRuntimeValidations":                     {OperationID: "listRuntimeValidations", Method: "GET", Path: "/api/v1/enterprise/runtime-validations", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<RuntimeValidation>"},
	"startRuntimeValidation":                     {OperationID: "startRuntimeValidation", Method: "POST", Path: "/api/v1/enterprise/runtime-validations", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "StartRuntimeValidationRequest", RequestRequired: true, ResponseSchema: "RuntimeValidation"},
	"completeRuntimeValidation":                  {OperationID: "completeRuntimeValidation", Method: "POST", Path: "/api/v1/enterprise/runtime-validations/{validationId}:complete", PathParameters: []string{"validationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CompleteRuntimeValidationRequest", RequestRequired: true, ResponseSchema: "RuntimeValidation"},
	"startRuntimeValidationMatrix":               {OperationID: "startRuntimeValidationMatrix", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-matrices", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "StartRuntimeValidationMatrixRequest", RequestRequired: true, ResponseSchema: "array<RuntimeValidation>"},
	"claimRuntimeValidationJob":                  {OperationID: "claimRuntimeValidationJob", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-jobs:claim", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "ClaimRuntimeValidationJobRequest", RequestRequired: true, ResponseSchema: "RuntimeValidationJobClaim"},
	"startClaimedRuntimeValidationJob":           {OperationID: "startClaimedRuntimeValidationJob", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-jobs/{validationId}:start", PathParameters: []string{"validationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RuntimeValidationJobClaimRequest", RequestRequired: true, ResponseSchema: "RuntimeValidationJob"},
	"heartbeatRuntimeValidationJob":              {OperationID: "heartbeatRuntimeValidationJob", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-jobs/{validationId}:heartbeat", PathParameters: []string{"validationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RuntimeValidationJobClaimRequest", RequestRequired: true, ResponseSchema: "RuntimeValidationJob"},
	"completeRuntimeValidationJob":               {OperationID: "completeRuntimeValidationJob", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-jobs/{validationId}:complete", PathParameters: []string{"validationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CompleteRuntimeValidationJobRequest", RequestRequired: true, ResponseSchema: "RuntimeValidation"},
	"failRuntimeValidationJob":                   {OperationID: "failRuntimeValidationJob", Method: "POST", Path: "/api/v1/enterprise/runtime-validation-jobs/{validationId}:fail", PathParameters: []string{"validationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "FailRuntimeValidationJobRequest", RequestRequired: true, ResponseSchema: "RuntimeValidation"},
	"listEnterpriseCostRates":                    {OperationID: "listEnterpriseCostRates", Method: "GET", Path: "/api/v1/enterprise/cost-rates", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<CostRate>"},
	"createEnterpriseCostRate":                   {OperationID: "createEnterpriseCostRate", Method: "POST", Path: "/api/v1/enterprise/cost-rates", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CreateCostRateRequest", RequestRequired: true, ResponseSchema: "CostRate"},
	"explainSessionCost":                         {OperationID: "explainSessionCost", Method: "GET", Path: "/api/v1/enterprise/sessions/{sessionId}/cost-explanation", PathParameters: []string{"sessionId"}, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "SessionCostExplanation"},
	"getTenantMediaQuota":                        {OperationID: "getTenantMediaQuota", Method: "GET", Path: "/api/v1/enterprise/media-quota", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "MediaQuota"},
	"upsertTenantMediaQuota":                     {OperationID: "upsertTenantMediaQuota", Method: "PUT", Path: "/api/v1/enterprise/media-quota", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpsertMediaQuotaRequest", RequestRequired: true, ResponseSchema: "MediaQuota"},
	"upsertSloPolicy":                            {OperationID: "upsertSloPolicy", Method: "PUT", Path: "/api/v1/enterprise/slo-policy", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "UpsertSloPolicyRequest", RequestRequired: true, ResponseSchema: "ErrorBudget"},
	"getErrorBudget":                             {OperationID: "getErrorBudget", Method: "GET", Path: "/api/v1/enterprise/error-budget", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ErrorBudget"},
	"getReleaseFreezeState":                      {OperationID: "getReleaseFreezeState", Method: "GET", Path: "/api/v1/enterprise/release-freeze", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ReleaseFreeze"},
	"recordServiceLevelEvent":                    {OperationID: "recordServiceLevelEvent", Method: "POST", Path: "/api/v1/enterprise/service-level-events", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "RecordServiceLevelEventRequest", RequestRequired: true, ResponseSchema: "ErrorBudget"},
	"listSlaExclusions":                          {OperationID: "listSlaExclusions", Method: "GET", Path: "/api/v1/enterprise/sla-exclusions", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<SlaExclusion>"},
	"upsertSlaExclusion":                         {OperationID: "upsertSlaExclusion", Method: "PUT", Path: "/api/v1/enterprise/sla-exclusions/{exclusionCode}", PathParameters: []string{"exclusionCode"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpsertSlaExclusionRequest", RequestRequired: true, ResponseSchema: "SlaExclusion"},
	"listRetentionPolicies":                      {OperationID: "listRetentionPolicies", Method: "GET", Path: "/api/v1/enterprise/retention-policies", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<RetentionPolicy>"},
	"upsertRetentionPolicy":                      {OperationID: "upsertRetentionPolicy", Method: "PUT", Path: "/api/v1/enterprise/retention-policies", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "UpsertRetentionPolicyRequest", RequestRequired: true, ResponseSchema: "RetentionPolicy"},
	"createRetentionDeletionReceipt":             {OperationID: "createRetentionDeletionReceipt", Method: "POST", Path: "/api/v1/enterprise/retention-deletion-receipts", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CreateDeletionReceiptRequest", RequestRequired: true, ResponseSchema: "DeletionReceipt"},
	"listLicenseInventory":                       {OperationID: "listLicenseInventory", Method: "GET", Path: "/api/v1/enterprise/license-inventory", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<LicenseInventory>"},
	"upsertLicenseInventory":                     {OperationID: "upsertLicenseInventory", Method: "PUT", Path: "/api/v1/enterprise/license-inventory/{componentId}", PathParameters: []string{"componentId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpsertLicenseInventoryRequest", RequestRequired: true, ResponseSchema: "LicenseInventory"},
	"generateAuditExportManifest":                {OperationID: "generateAuditExportManifest", Method: "POST", Path: "/api/v1/enterprise/audit-exports", PathParameters: nil, QueryParameters: []string{"fromSequence", "toSequence"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "AuditExportManifest"},
	"listEnterpriseRegions":                      {OperationID: "listEnterpriseRegions", Method: "GET", Path: "/api/v1/enterprise/regions", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<EnterpriseRegion>"},
	"upsertEnterpriseRegion":                     {OperationID: "upsertEnterpriseRegion", Method: "PUT", Path: "/api/v1/enterprise/regions/{regionId}", PathParameters: []string{"regionId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpsertRegionRequest", RequestRequired: true, ResponseSchema: "EnterpriseRegion"},
	"listRecoveryGameDays":                       {OperationID: "listRecoveryGameDays", Method: "GET", Path: "/api/v1/enterprise/recovery-gamedays", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<RecoveryGameDay>"},
	"startRecoveryGameDay":                       {OperationID: "startRecoveryGameDay", Method: "POST", Path: "/api/v1/enterprise/recovery-gamedays", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "StartRecoveryGameDayRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDay"},
	"completeRecoveryGameDay":                    {OperationID: "completeRecoveryGameDay", Method: "POST", Path: "/api/v1/enterprise/recovery-gamedays/{gameDayId}:complete", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CompleteRecoveryGameDayRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDay"},
	"getRecoveryGameDay":                         {OperationID: "getRecoveryGameDay", Method: "GET", Path: "/api/v1/enterprise/recovery-gamedays/{gameDayId}", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryGameDay"},
	"listRecoveryGameDayEvents":                  {OperationID: "listRecoveryGameDayEvents", Method: "GET", Path: "/api/v1/enterprise/recovery-gamedays/{gameDayId}/events", PathParameters: []string{"gameDayId"}, QueryParameters: []string{"cursor", "limit"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryGameDayEventPage"},
	"listRecoveryGameDayTrends":                  {OperationID: "listRecoveryGameDayTrends", Method: "GET", Path: "/api/v1/enterprise/recovery-gameday-trends", PathParameters: nil, QueryParameters: []string{"windowDays"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<RecoveryGameDayTrend>"},
	"generateRecoveryGameDayReport":              {OperationID: "generateRecoveryGameDayReport", Method: "POST", Path: "/api/v1/enterprise/recovery-gamedays/{gameDayId}/exports", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryGameDayReportExport"},
	"getRecoveryGameDayReport":                   {OperationID: "getRecoveryGameDayReport", Method: "GET", Path: "/api/v1/enterprise/recovery-gameday-exports/{exportId}", PathParameters: []string{"exportId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryGameDayReportExport"},
	"listRecoveryGameDayRemediations":            {OperationID: "listRecoveryGameDayRemediations", Method: "GET", Path: "/api/v1/enterprise/recovery-gameday-remediations", PathParameters: nil, QueryParameters: []string{"state"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "array<RecoveryGameDayRemediation>"},
	"updateRecoveryGameDayRemediation":           {OperationID: "updateRecoveryGameDayRemediation", Method: "PUT", Path: "/api/v1/enterprise/recovery-gameday-remediations/{ticketId}", PathParameters: []string{"ticketId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpdateRecoveryGameDayRemediationRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDayRemediation"},
	"abortRecoveryGameDay":                       {OperationID: "abortRecoveryGameDay", Method: "POST", Path: "/api/v1/enterprise/recovery-gamedays/{gameDayId}:abort", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "RecoveryGameDay"},
	"claimRecoveryGameDayJob":                    {OperationID: "claimRecoveryGameDayJob", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs:claim", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "ClaimRecoveryGameDayJobRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDayJobClaim"},
	"startRecoveryGameDayJob":                    {OperationID: "startRecoveryGameDayJob", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:start", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RecoveryGameDayJobClaimRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDayJob"},
	"heartbeatRecoveryGameDayJob":                {OperationID: "heartbeatRecoveryGameDayJob", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:heartbeat", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "RecoveryGameDayJobClaimRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDayJob"},
	"updateRecoveryGameDayJobStage":              {OperationID: "updateRecoveryGameDayJobStage", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:stage", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "UpdateRecoveryGameDayStageRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDayJob"},
	"completeRecoveryGameDayJob":                 {OperationID: "completeRecoveryGameDayJob", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:complete", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "CompleteRecoveryGameDayJobRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDay"},
	"failRecoveryGameDayJob":                     {OperationID: "failRecoveryGameDayJob", Method: "POST", Path: "/api/v1/enterprise/recovery-gameday-jobs/{gameDayId}:fail", PathParameters: []string{"gameDayId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "FailRecoveryGameDayJobRequest", RequestRequired: true, ResponseSchema: "RecoveryGameDay"},
	"generateComplianceSnapshot":                 {OperationID: "generateComplianceSnapshot", Method: "POST", Path: "/api/v1/enterprise/compliance-snapshots", PathParameters: nil, QueryParameters: []string{"framework"}, HeaderParameters: []string{"X-Tenant-Id"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "ComplianceSnapshot"},
	"listEnvironmentSavedViews":                  {OperationID: "listEnvironmentSavedViews", Method: "GET", Path: "/api/v1/environment-saved-views", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "EnvironmentSavedViewListResponse"},
	"createEnvironmentSavedView":                 {OperationID: "createEnvironmentSavedView", Method: "POST", Path: "/api/v1/environment-saved-views", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CreateEnvironmentSavedViewRequest", RequestRequired: true, ResponseSchema: "EnvironmentSavedView"},
	"updateEnvironmentSavedView":                 {OperationID: "updateEnvironmentSavedView", Method: "PUT", Path: "/api/v1/environment-saved-views/{savedViewId}", PathParameters: []string{"savedViewId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "UpdateEnvironmentSavedViewRequest", RequestRequired: true, ResponseSchema: "EnvironmentSavedView"},
	"deleteEnvironmentSavedView":                 {OperationID: "deleteEnvironmentSavedView", Method: "DELETE", Path: "/api/v1/environment-saved-views/{savedViewId}", PathParameters: []string{"savedViewId"}, QueryParameters: []string{"expectedVersion"}, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: ""},
	"listEnvironmentImports":                     {OperationID: "listEnvironmentImports", Method: "GET", Path: "/api/v1/environment-imports", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "EnvironmentImportListResponse"},
	"previewEnvironmentImport":                   {OperationID: "previewEnvironmentImport", Method: "POST", Path: "/api/v1/environment-imports:preview", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "PreviewEnvironmentImportRequest", RequestRequired: true, ResponseSchema: "EnvironmentImport"},
	"getEnvironmentImport":                       {OperationID: "getEnvironmentImport", Method: "GET", Path: "/api/v1/environment-imports/{importId}", PathParameters: []string{"importId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "EnvironmentImport"},
	"commitEnvironmentImport":                    {OperationID: "commitEnvironmentImport", Method: "POST", Path: "/api/v1/environment-imports/{importId}:commit", PathParameters: []string{"importId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CommitEnvironmentImportRequest", RequestRequired: true, ResponseSchema: "EnvironmentImport"},
	"listWorkspaceGroups":                        {OperationID: "listWorkspaceGroups", Method: "GET", Path: "/api/v1/groups", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceGroupListResponse"},
	"createWorkspaceGroup":                       {OperationID: "createWorkspaceGroup", Method: "POST", Path: "/api/v1/groups", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "WorkspaceGroupRequest", RequestRequired: true, ResponseSchema: "WorkspaceGroup"},
	"updateWorkspaceGroup":                       {OperationID: "updateWorkspaceGroup", Method: "PUT", Path: "/api/v1/groups/{groupId}", PathParameters: []string{"groupId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "WorkspaceGroupRequest", RequestRequired: true, ResponseSchema: "WorkspaceGroup"},
	"deleteWorkspaceGroup":                       {OperationID: "deleteWorkspaceGroup", Method: "DELETE", Path: "/api/v1/groups/{groupId}", PathParameters: []string{"groupId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: ""},
	"assignSessionToWorkspaceGroup":              {OperationID: "assignSessionToWorkspaceGroup", Method: "PUT", Path: "/api/v1/groups/{groupId}/sessions/{sessionId}", PathParameters: []string{"groupId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceGroup"},
	"unassignSessionFromWorkspaceGroup":          {OperationID: "unassignSessionFromWorkspaceGroup", Method: "DELETE", Path: "/api/v1/groups/{groupId}/sessions/{sessionId}", PathParameters: []string{"groupId", "sessionId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceGroup"},
	"listWorkspaceTags":                          {OperationID: "listWorkspaceTags", Method: "GET", Path: "/api/v1/tags", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceTagListResponse"},
	"createWorkspaceTag":                         {OperationID: "createWorkspaceTag", Method: "POST", Path: "/api/v1/tags", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "WorkspaceTagRequest", RequestRequired: true, ResponseSchema: "WorkspaceTag"},
	"updateWorkspaceTag":                         {OperationID: "updateWorkspaceTag", Method: "PUT", Path: "/api/v1/tags/{tagId}", PathParameters: []string{"tagId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "WorkspaceTagRequest", RequestRequired: true, ResponseSchema: "WorkspaceTag"},
	"deleteWorkspaceTag":                         {OperationID: "deleteWorkspaceTag", Method: "DELETE", Path: "/api/v1/tags/{tagId}", PathParameters: []string{"tagId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: ""},
	"assignSessionToWorkspaceTag":                {OperationID: "assignSessionToWorkspaceTag", Method: "PUT", Path: "/api/v1/tags/{tagId}/sessions/{sessionId}", PathParameters: []string{"sessionId", "tagId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceTag"},
	"unassignSessionFromWorkspaceTag":            {OperationID: "unassignSessionFromWorkspaceTag", Method: "DELETE", Path: "/api/v1/tags/{tagId}/sessions/{sessionId}", PathParameters: []string{"sessionId", "tagId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceTag"},
	"listWorkspaceBatchOperations":               {OperationID: "listWorkspaceBatchOperations", Method: "GET", Path: "/api/v1/workspace-batch-operations", PathParameters: nil, QueryParameters: []string{"limit"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceBatchOperationListResponse"},
	"createWorkspaceBatchOperation":              {OperationID: "createWorkspaceBatchOperation", Method: "POST", Path: "/api/v1/workspace-batch-operations", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CreateWorkspaceBatchOperationRequest", RequestRequired: true, ResponseSchema: "WorkspaceBatchOperation"},
	"getWorkspaceBatchOperation":                 {OperationID: "getWorkspaceBatchOperation", Method: "GET", Path: "/api/v1/workspace-batch-operations/{batchOperationId}", PathParameters: []string{"batchOperationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceBatchOperation"},
	"cancelWorkspaceBatchOperation":              {OperationID: "cancelWorkspaceBatchOperation", Method: "POST", Path: "/api/v1/workspace-batch-operations/{batchOperationId}:cancel", PathParameters: []string{"batchOperationId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CancelWorkspaceBatchOperationRequest", RequestRequired: true, ResponseSchema: "WorkspaceBatchOperation"},
	"listWorkspaceMetadataBatchOperations":       {OperationID: "listWorkspaceMetadataBatchOperations", Method: "GET", Path: "/api/v1/workspace-metadata-batch-operations", PathParameters: nil, QueryParameters: []string{"limit"}, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceMetadataBatchOperationListResponse"},
	"createWorkspaceMetadataBatchOperation":      {OperationID: "createWorkspaceMetadataBatchOperation", Method: "POST", Path: "/api/v1/workspace-metadata-batch-operations", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CreateWorkspaceMetadataBatchOperationRequest", RequestRequired: true, ResponseSchema: "WorkspaceMetadataBatchOperation"},
	"getWorkspaceMetadataBatchOperation":         {OperationID: "getWorkspaceMetadataBatchOperation", Method: "GET", Path: "/api/v1/workspace-metadata-batch-operations/{batchOperationId}", PathParameters: []string{"batchOperationId"}, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceMetadataBatchOperation"},
	"cancelWorkspaceMetadataBatchOperation":      {OperationID: "cancelWorkspaceMetadataBatchOperation", Method: "POST", Path: "/api/v1/workspace-metadata-batch-operations/{batchOperationId}:cancel", PathParameters: []string{"batchOperationId"}, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "CancelWorkspaceBatchOperationRequest", RequestRequired: true, ResponseSchema: "WorkspaceMetadataBatchOperation"},
	"getWorkspaceSettings":                       {OperationID: "getWorkspaceSettings", Method: "GET", Path: "/api/v1/workspace-settings", PathParameters: nil, QueryParameters: nil, HeaderParameters: nil, RequestSchema: "", RequestRequired: false, ResponseSchema: "WorkspaceSettings"},
	"updateWorkspaceSettings":                    {OperationID: "updateWorkspaceSettings", Method: "PUT", Path: "/api/v1/workspace-settings", PathParameters: nil, QueryParameters: nil, HeaderParameters: []string{"Idempotency-Key"}, RequestSchema: "WorkspaceSettingsRequest", RequestRequired: true, ResponseSchema: "WorkspaceSettings"},
}

func New(options Options) (*Client, error) {
	parsed, err := url.Parse(options.BaseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, errors.New("base URL must be an absolute HTTP(S) URL")
	}
	if options.TenantID == "" {
		return nil, errors.New("tenant ID is required")
	}
	client := options.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{baseURL: strings.TrimRight(options.BaseURL, "/"), tenantID: options.TenantID, accessToken: options.AccessToken, actorID: options.ActorID, httpClient: client}, nil
}

func (c *Client) Call(ctx context.Context, operationID string, request Request) (any, *http.Response, error) {
	operation, ok := Operations[operationID]
	if !ok {
		return nil, nil, fmt.Errorf("unknown OpenAPI operation: %s", operationID)
	}
	route := operation.Path
	for _, name := range operation.PathParameters {
		value, exists := request.Path[name]
		if !exists {
			return nil, nil, fmt.Errorf("missing path parameter %s for %s", name, operationID)
		}
		route = strings.ReplaceAll(route, "{"+name+"}", url.PathEscape(value))
	}
	allowedQuery := map[string]bool{}
	for _, name := range operation.QueryParameters {
		allowedQuery[name] = true
	}
	for name := range request.Query {
		if !allowedQuery[name] {
			return nil, nil, fmt.Errorf("unknown query parameter %s for %s", name, operationID)
		}
	}
	if operation.RequestRequired && request.Body == nil {
		return nil, nil, fmt.Errorf("request body is required for %s", operationID)
	}
	controlledHeaders := map[string]bool{"authorization": true, "x-tenant-id": true, "x-actor-id": true}
	allowedHeaders := map[string]bool{}
	for _, name := range operation.HeaderParameters {
		normalized := strings.ToLower(name)
		if !controlledHeaders[normalized] {
			allowedHeaders[normalized] = true
		}
	}
	for name := range request.Headers {
		if !allowedHeaders[strings.ToLower(name)] {
			return nil, nil, fmt.Errorf("unknown or identity-controlled header %s for %s", name, operationID)
		}
	}
	var body io.Reader
	if request.Body != nil {
		payload, err := json.Marshal(request.Body)
		if err != nil {
			return nil, nil, err
		}
		body = bytes.NewReader(payload)
	}
	endpoint := c.baseURL + route
	if encoded := request.Query.Encode(); encoded != "" {
		endpoint += "?" + encoded
	}
	httpRequest, err := http.NewRequestWithContext(ctx, operation.Method, endpoint, body)
	if err != nil {
		return nil, nil, err
	}
	httpRequest.Header.Set("Accept", "application/json")
	httpRequest.Header.Set("Content-Type", "application/json")
	for name, values := range request.Headers {
		for _, value := range values {
			httpRequest.Header.Add(name, value)
		}
	}
	if c.accessToken != "" {
		httpRequest.Header.Set("Authorization", "Bearer "+c.accessToken)
	} else {
		httpRequest.Header.Set("X-Tenant-Id", c.tenantID)
		if c.actorID != "" {
			httpRequest.Header.Set("X-Actor-Id", c.actorID)
		}
	}
	response, err := c.httpClient.Do(httpRequest)
	if err != nil {
		return nil, nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, response, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var envelope struct{ Code, Message, RequestID string }
		_ = json.Unmarshal(payload, &envelope)
		return nil, response, &APIError{response.StatusCode, envelope.Code, envelope.Message, envelope.RequestID}
	}
	if len(payload) == 0 {
		return nil, response, nil
	}
	var result any
	if strings.Contains(response.Header.Get("Content-Type"), "json") || payload[0] == '{' || payload[0] == '[' {
		if err := json.Unmarshal(payload, &result); err != nil {
			return nil, response, err
		}
		return result, response, nil
	}
	return payload, response, nil
}

func (c *Client) GetWorkspaceOverview(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getWorkspaceOverview", request)
}
func (c *Client) StreamWorkspaceOverviewChanges(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "streamWorkspaceOverviewChanges", request)
}
func (c *Client) GetTenantCoordinatorRoute(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getTenantCoordinatorRoute", request)
}
func (c *Client) GetLatestTenantCoordinatorRouteMigration(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getLatestTenantCoordinatorRouteMigration", request)
}
func (c *Client) RequestTenantCoordinatorRouteMigration(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestTenantCoordinatorRouteMigration", request)
}
func (c *Client) GlobalSearch(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "globalSearch", request)
}
func (c *Client) ListWorkspaceNotifications(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listWorkspaceNotifications", request)
}
func (c *Client) StreamWorkspaceNotificationChanges(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "streamWorkspaceNotificationChanges", request)
}
func (c *Client) UpdateWorkspaceNotificationReadCursor(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateWorkspaceNotificationReadCursor", request)
}
func (c *Client) GetUserPreferences(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getUserPreferences", request)
}
func (c *Client) UpdateUserPreferences(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateUserPreferences", request)
}
func (c *Client) ListSessions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSessions", request)
}
func (c *Client) CreateSession(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createSession", request)
}
func (c *Client) GetSession(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getSession", request)
}
func (c *Client) GetBrowserState(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getBrowserState", request)
}
func (c *Client) GetSessionResources(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getSessionResources", request)
}
func (c *Client) ListSessionResourceEvents(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSessionResourceEvents", request)
}
func (c *Client) ListSessionEvidence(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSessionEvidence", request)
}
func (c *Client) CaptureSessionEvidence(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "captureSessionEvidence", request)
}
func (c *Client) GetSessionEvidenceCapture(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getSessionEvidenceCapture", request)
}
func (c *Client) CreateSessionEvidenceAccessGrant(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createSessionEvidenceAccessGrant", request)
}
func (c *Client) RedeemSessionEvidenceAccessGrant(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "redeemSessionEvidenceAccessGrant", request)
}
func (c *Client) StreamSessionResourceChanges(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "streamSessionResourceChanges", request)
}
func (c *Client) StreamSessionChanges(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "streamSessionChanges", request)
}
func (c *Client) GetSessionSafePoint(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getSessionSafePoint", request)
}
func (c *Client) ListSessionSafetyLeases(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSessionSafetyLeases", request)
}
func (c *Client) AcquireSessionSafetyLease(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "acquireSessionSafetyLease", request)
}
func (c *Client) RenewSessionSafetyLease(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "renewSessionSafetyLease", request)
}
func (c *Client) ReleaseSessionSafetyLease(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "releaseSessionSafetyLease", request)
}
func (c *Client) GetLatestSessionMigration(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getLatestSessionMigration", request)
}
func (c *Client) RebindSessionProxy(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rebindSessionProxy", request)
}
func (c *Client) GetLatestSessionProxyRebind(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getLatestSessionProxyRebind", request)
}
func (c *Client) GetBusinessRecoveryValidation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getBusinessRecoveryValidation", request)
}
func (c *Client) ValidateBusinessRecovery(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "validateBusinessRecovery", request)
}
func (c *Client) ListBusinessRecoveryProviderEvidence(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listBusinessRecoveryProviderEvidence", request)
}
func (c *Client) SubmitBusinessRecoveryProviderEvidence(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "submitBusinessRecoveryProviderEvidence", request)
}
func (c *Client) GetSessionApplicationBinding(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getSessionApplicationBinding", request)
}
func (c *Client) RebindSessionApplicationContract(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rebindSessionApplicationContract", request)
}
func (c *Client) ListApplicationRecoveryContracts(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listApplicationRecoveryContracts", request)
}
func (c *Client) GetApplicationRecoveryContract(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getApplicationRecoveryContract", request)
}
func (c *Client) UpsertApplicationRecoveryContract(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertApplicationRecoveryContract", request)
}
func (c *Client) ListApplicationRecoveryContractRevisions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listApplicationRecoveryContractRevisions", request)
}
func (c *Client) DiffApplicationRecoveryContractRevisions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "diffApplicationRecoveryContractRevisions", request)
}
func (c *Client) RestoreApplicationRecoveryContractRevision(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "restoreApplicationRecoveryContractRevision", request)
}
func (c *Client) RequestApplicationRecoveryContractApproval(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestApplicationRecoveryContractApproval", request)
}
func (c *Client) ApproveApplicationRecoveryContract(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "approveApplicationRecoveryContract", request)
}
func (c *Client) RejectApplicationRecoveryContract(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rejectApplicationRecoveryContract", request)
}
func (c *Client) UpdateSessionResourcePolicy(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateSessionResourcePolicy", request)
}
func (c *Client) StartSession(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startSession", request)
}
func (c *Client) ResyncBrowserState(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "resyncBrowserState", request)
}
func (c *Client) TerminateSession(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "terminateSession", request)
}
func (c *Client) RequestHumanTakeover(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestHumanTakeover", request)
}
func (c *Client) ReleaseHumanTakeover(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "releaseHumanTakeover", request)
}
func (c *Client) CreateRemoteDesktopConnection(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createRemoteDesktopConnection", request)
}
func (c *Client) ListRemoteDesktopParticipants(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRemoteDesktopParticipants", request)
}
func (c *Client) RevokeRemoteDesktopParticipant(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "revokeRemoteDesktopParticipant", request)
}
func (c *Client) ListProfiles(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listProfiles", request)
}
func (c *Client) CreateProfile(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createProfile", request)
}
func (c *Client) GetProfile(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getProfile", request)
}
func (c *Client) ListProfileImports(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listProfileImports", request)
}
func (c *Client) ImportProfileCheckpoint(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "importProfileCheckpoint", request)
}
func (c *Client) GetProfileImport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getProfileImport", request)
}
func (c *Client) GetProxyOverview(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getProxyOverview", request)
}
func (c *Client) ListProxyBindings(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listProxyBindings", request)
}
func (c *Client) CreateProxyBinding(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createProxyBinding", request)
}
func (c *Client) UpdateProxyBinding(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateProxyBinding", request)
}
func (c *Client) DeleteProxyBinding(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "deleteProxyBinding", request)
}
func (c *Client) CreateAgentTask(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createAgentTask", request)
}
func (c *Client) ListSessionChallenges(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSessionChallenges", request)
}
func (c *Client) GetChallengeEvent(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getChallengeEvent", request)
}
func (c *Client) PreviewHumanAssist(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "previewHumanAssist", request)
}
func (c *Client) AuthorizeHumanAssist(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "authorizeHumanAssist", request)
}
func (c *Client) ListAgentTasks(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listAgentTasks", request)
}
func (c *Client) ListAgentTaskSummaries(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listAgentTaskSummaries", request)
}
func (c *Client) GetAgentTask(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getAgentTask", request)
}
func (c *Client) ExecuteAgentTask(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "executeAgentTask", request)
}
func (c *Client) ClaimAgentExecutionJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "claimAgentExecutionJob", request)
}
func (c *Client) StartAgentExecutionJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startAgentExecutionJob", request)
}
func (c *Client) HeartbeatAgentExecutionJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "heartbeatAgentExecutionJob", request)
}
func (c *Client) DriveAgentExecutionJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "driveAgentExecutionJob", request)
}
func (c *Client) FailAgentExecutionJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "failAgentExecutionJob", request)
}
func (c *Client) ClaimAgentReviewJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "claimAgentReviewJob", request)
}
func (c *Client) StartAgentReviewJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startAgentReviewJob", request)
}
func (c *Client) HeartbeatAgentReviewJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "heartbeatAgentReviewJob", request)
}
func (c *Client) CompleteAgentReviewJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeAgentReviewJob", request)
}
func (c *Client) FailAgentReviewJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "failAgentReviewJob", request)
}
func (c *Client) ApproveAgentTask(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "approveAgentTask", request)
}
func (c *Client) RejectAgentTask(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rejectAgentTask", request)
}
func (c *Client) AcceptAgentHandoff(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "acceptAgentHandoff", request)
}
func (c *Client) RejectAgentHandoff(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rejectAgentHandoff", request)
}
func (c *Client) ListAuditEvents(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listAuditEvents", request)
}
func (c *Client) ListRuntimeBuilds(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRuntimeBuilds", request)
}
func (c *Client) RequestRuntimePromotion(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestRuntimePromotion", request)
}
func (c *Client) RequestRuntimeDisable(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestRuntimeDisable", request)
}
func (c *Client) ListRuntimeReleaseRequests(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRuntimeReleaseRequests", request)
}
func (c *Client) ApproveRuntimeRelease(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "approveRuntimeRelease", request)
}
func (c *Client) RejectRuntimeRelease(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rejectRuntimeRelease", request)
}
func (c *Client) ListKeyRotationRequests(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listKeyRotationRequests", request)
}
func (c *Client) RequestKeyRotation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestKeyRotation", request)
}
func (c *Client) ApproveKeyRotation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "approveKeyRotation", request)
}
func (c *Client) CompleteKeyRotation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeKeyRotation", request)
}
func (c *Client) RevokeKeyRotation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "revokeKeyRotation", request)
}
func (c *Client) ListBreakGlassRequests(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listBreakGlassRequests", request)
}
func (c *Client) RequestBreakGlass(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "requestBreakGlass", request)
}
func (c *Client) ApproveBreakGlass(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "approveBreakGlass", request)
}
func (c *Client) RejectBreakGlass(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "rejectBreakGlass", request)
}
func (c *Client) RevokeBreakGlass(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "revokeBreakGlass", request)
}
func (c *Client) ReviewBreakGlass(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "reviewBreakGlass", request)
}
func (c *Client) StartSecureDebug(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startSecureDebug", request)
}
func (c *Client) ListSecureDebugSessions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSecureDebugSessions", request)
}
func (c *Client) ReadSecureDebugSnapshot(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "readSecureDebugSnapshot", request)
}
func (c *Client) EndSecureDebug(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "endSecureDebug", request)
}
func (c *Client) ListBrowserNodes(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listBrowserNodes", request)
}
func (c *Client) RegisterBrowserNode(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "registerBrowserNode", request)
}
func (c *Client) ReportBrowserNodePressure(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "reportBrowserNodePressure", request)
}
func (c *Client) ListExtensionProfiles(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listExtensionProfiles", request)
}
func (c *Client) UpsertExtensionProfile(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertExtensionProfile", request)
}
func (c *Client) RecordExtensionProfileSample(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "recordExtensionProfileSample", request)
}
func (c *Client) GetBrowserPlacement(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getBrowserPlacement", request)
}
func (c *Client) GetEnterpriseOverview(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getEnterpriseOverview", request)
}
func (c *Client) ListRuntimeValidations(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRuntimeValidations", request)
}
func (c *Client) StartRuntimeValidation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startRuntimeValidation", request)
}
func (c *Client) CompleteRuntimeValidation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeRuntimeValidation", request)
}
func (c *Client) StartRuntimeValidationMatrix(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startRuntimeValidationMatrix", request)
}
func (c *Client) ClaimRuntimeValidationJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "claimRuntimeValidationJob", request)
}
func (c *Client) StartClaimedRuntimeValidationJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startClaimedRuntimeValidationJob", request)
}
func (c *Client) HeartbeatRuntimeValidationJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "heartbeatRuntimeValidationJob", request)
}
func (c *Client) CompleteRuntimeValidationJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeRuntimeValidationJob", request)
}
func (c *Client) FailRuntimeValidationJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "failRuntimeValidationJob", request)
}
func (c *Client) ListEnterpriseCostRates(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listEnterpriseCostRates", request)
}
func (c *Client) CreateEnterpriseCostRate(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createEnterpriseCostRate", request)
}
func (c *Client) ExplainSessionCost(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "explainSessionCost", request)
}
func (c *Client) GetTenantMediaQuota(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getTenantMediaQuota", request)
}
func (c *Client) UpsertTenantMediaQuota(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertTenantMediaQuota", request)
}
func (c *Client) UpsertSloPolicy(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertSloPolicy", request)
}
func (c *Client) GetErrorBudget(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getErrorBudget", request)
}
func (c *Client) GetReleaseFreezeState(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getReleaseFreezeState", request)
}
func (c *Client) RecordServiceLevelEvent(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "recordServiceLevelEvent", request)
}
func (c *Client) ListSlaExclusions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listSlaExclusions", request)
}
func (c *Client) UpsertSlaExclusion(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertSlaExclusion", request)
}
func (c *Client) ListRetentionPolicies(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRetentionPolicies", request)
}
func (c *Client) UpsertRetentionPolicy(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertRetentionPolicy", request)
}
func (c *Client) CreateRetentionDeletionReceipt(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createRetentionDeletionReceipt", request)
}
func (c *Client) ListLicenseInventory(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listLicenseInventory", request)
}
func (c *Client) UpsertLicenseInventory(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertLicenseInventory", request)
}
func (c *Client) GenerateAuditExportManifest(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "generateAuditExportManifest", request)
}
func (c *Client) ListEnterpriseRegions(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listEnterpriseRegions", request)
}
func (c *Client) UpsertEnterpriseRegion(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "upsertEnterpriseRegion", request)
}
func (c *Client) ListRecoveryGameDays(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRecoveryGameDays", request)
}
func (c *Client) StartRecoveryGameDay(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startRecoveryGameDay", request)
}
func (c *Client) CompleteRecoveryGameDay(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeRecoveryGameDay", request)
}
func (c *Client) GetRecoveryGameDay(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getRecoveryGameDay", request)
}
func (c *Client) ListRecoveryGameDayEvents(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRecoveryGameDayEvents", request)
}
func (c *Client) ListRecoveryGameDayTrends(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRecoveryGameDayTrends", request)
}
func (c *Client) GenerateRecoveryGameDayReport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "generateRecoveryGameDayReport", request)
}
func (c *Client) GetRecoveryGameDayReport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getRecoveryGameDayReport", request)
}
func (c *Client) ListRecoveryGameDayRemediations(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listRecoveryGameDayRemediations", request)
}
func (c *Client) UpdateRecoveryGameDayRemediation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateRecoveryGameDayRemediation", request)
}
func (c *Client) AbortRecoveryGameDay(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "abortRecoveryGameDay", request)
}
func (c *Client) ClaimRecoveryGameDayJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "claimRecoveryGameDayJob", request)
}
func (c *Client) StartRecoveryGameDayJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "startRecoveryGameDayJob", request)
}
func (c *Client) HeartbeatRecoveryGameDayJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "heartbeatRecoveryGameDayJob", request)
}
func (c *Client) UpdateRecoveryGameDayJobStage(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateRecoveryGameDayJobStage", request)
}
func (c *Client) CompleteRecoveryGameDayJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "completeRecoveryGameDayJob", request)
}
func (c *Client) FailRecoveryGameDayJob(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "failRecoveryGameDayJob", request)
}
func (c *Client) GenerateComplianceSnapshot(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "generateComplianceSnapshot", request)
}
func (c *Client) ListEnvironmentSavedViews(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listEnvironmentSavedViews", request)
}
func (c *Client) CreateEnvironmentSavedView(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createEnvironmentSavedView", request)
}
func (c *Client) UpdateEnvironmentSavedView(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateEnvironmentSavedView", request)
}
func (c *Client) DeleteEnvironmentSavedView(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "deleteEnvironmentSavedView", request)
}
func (c *Client) ListEnvironmentImports(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listEnvironmentImports", request)
}
func (c *Client) PreviewEnvironmentImport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "previewEnvironmentImport", request)
}
func (c *Client) GetEnvironmentImport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getEnvironmentImport", request)
}
func (c *Client) CommitEnvironmentImport(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "commitEnvironmentImport", request)
}
func (c *Client) ListWorkspaceGroups(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listWorkspaceGroups", request)
}
func (c *Client) CreateWorkspaceGroup(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createWorkspaceGroup", request)
}
func (c *Client) UpdateWorkspaceGroup(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateWorkspaceGroup", request)
}
func (c *Client) DeleteWorkspaceGroup(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "deleteWorkspaceGroup", request)
}
func (c *Client) AssignSessionToWorkspaceGroup(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "assignSessionToWorkspaceGroup", request)
}
func (c *Client) UnassignSessionFromWorkspaceGroup(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "unassignSessionFromWorkspaceGroup", request)
}
func (c *Client) ListWorkspaceTags(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listWorkspaceTags", request)
}
func (c *Client) CreateWorkspaceTag(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createWorkspaceTag", request)
}
func (c *Client) UpdateWorkspaceTag(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateWorkspaceTag", request)
}
func (c *Client) DeleteWorkspaceTag(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "deleteWorkspaceTag", request)
}
func (c *Client) AssignSessionToWorkspaceTag(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "assignSessionToWorkspaceTag", request)
}
func (c *Client) UnassignSessionFromWorkspaceTag(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "unassignSessionFromWorkspaceTag", request)
}
func (c *Client) ListWorkspaceBatchOperations(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listWorkspaceBatchOperations", request)
}
func (c *Client) CreateWorkspaceBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createWorkspaceBatchOperation", request)
}
func (c *Client) GetWorkspaceBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getWorkspaceBatchOperation", request)
}
func (c *Client) CancelWorkspaceBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "cancelWorkspaceBatchOperation", request)
}
func (c *Client) ListWorkspaceMetadataBatchOperations(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "listWorkspaceMetadataBatchOperations", request)
}
func (c *Client) CreateWorkspaceMetadataBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "createWorkspaceMetadataBatchOperation", request)
}
func (c *Client) GetWorkspaceMetadataBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getWorkspaceMetadataBatchOperation", request)
}
func (c *Client) CancelWorkspaceMetadataBatchOperation(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "cancelWorkspaceMetadataBatchOperation", request)
}
func (c *Client) GetWorkspaceSettings(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "getWorkspaceSettings", request)
}
func (c *Client) UpdateWorkspaceSettings(ctx context.Context, request Request) (any, *http.Response, error) {
	return c.Call(ctx, "updateWorkspaceSettings", request)
}
