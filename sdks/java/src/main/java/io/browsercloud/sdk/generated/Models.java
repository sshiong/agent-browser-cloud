// Code generated from session-api.yaml; DO NOT EDIT.
package io.browsercloud.sdk.generated;

import java.util.List;
import java.util.Map;

public final class Models {
  private Models() {}

  public record WorkspaceOverview(WorkspaceSessionSummary sessions, WorkspaceOperationSummary operations, WorkspaceBrowserNodeSummary browserNodes, WorkspaceProxySummary proxies, WorkspaceAgentSummary agents, WorkspaceCostSummary cost, WorkspaceSecuritySummary security, Long cursor, String generatedAt) {}

  public record WorkspaceSessionSummary(Long total, Long running, Long pending, Long unhealthy, Long hibernated, Long terminated) {}

  public record WorkspaceOperationSummary(Long active) {}

  public record WorkspaceBrowserNodeSummary(Boolean visible, Long total, Long ready, Long constrained, Long activeSessions, Long maximumSessions, Long reservedCpuMillis, Long certifiedCpuMillis, Long reservedMemoryMib, Long certifiedMemoryMib) {}

  public record WorkspaceProxySummary(Long activeAllocations, Long boundSessions) {}

  public record WorkspaceAgentSummary(Long active, Long awaitingHuman, Long pausedByResourcePolicy, Long failedLast24Hours) {}

  public record WorkspaceCostSummary(Double currentHourlyUsd, Long activeSessionsWithoutCurrentPrice) {}

  public record WorkspaceSecuritySummary(Long warningLast24Hours, Long criticalLast24Hours) {}

  public enum SearchResourceType { SESSION, PROFILE, GROUP, TAG, RUNTIME, NODE }

  public record GlobalSearchResult(SearchResourceType resourceType, String resourceId, String title, Object description, Object status, Object region, Object updatedAt) {}

  public record GlobalSearchResponse(String query, List<GlobalSearchResult> items, Integer limit, Boolean truncated) {}

  public enum NotificationCategory { SECURITY, RESOURCE, AGENT, RELEASE, SYSTEM }

  public enum NotificationSeverity { INFO, WARNING, CRITICAL }

  public record WorkspaceNotification(String notificationId, Long sequence, NotificationCategory category, NotificationSeverity severity, String title, String body, String eventType, Object sessionId, Object resourceType, Object resourceId, Object requestId, String route, Boolean read, String occurredAt) {}

  public record WorkspaceNotificationListResponse(List<WorkspaceNotification> items, Long unreadCount, Long lastReadSequence, Long headSequence, Object nextBeforeSequence) {}

  public record UpdateNotificationReadCursorRequest(Long readThroughSequence) {}

  public record WorkspaceNotificationReadState(Long lastReadSequence, Long unreadCount, String updatedAt) {}

  public enum ThemeMode { SYSTEM, DARK, LIGHT }

  public record UpdateUserPreferencesRequest(ThemeMode themeMode) {}

  public record UserPreferences(ThemeMode themeMode, String source, Object updatedAt, Long version) {}

  public record TenantRoute(String tenantId, String state, Integer activeVirtualPartitions, Long activeRouteEpoch, Object pendingVirtualPartitions, Object pendingRouteEpoch, Object activeMigrationId, Long version, String updatedAt) {}

  public record RequestTenantRouteMigration(Long expectedRouteEpoch, Integer targetVirtualPartitions) {}

  public record TenantRouteMigration(String migrationId, String tenantId, Long sourceRouteEpoch, Long targetRouteEpoch, Integer sourceVirtualPartitions, Integer targetVirtualPartitions, String state, Integer totalSessions, Integer migratedSessions, Integer blockedSessions, String requestedBy, String requestId, Object failureCode, String createdAt, String updatedAt, Object completedAt) {}

  public record CreateAgentTaskRequest(String goal, String startUrl, List<String> allowedDomains, Integer maxActions, Integer replanBudget, List<AgentInstructionSource> contextSources, List<AgentActionRequest> actions) {}

  public record AgentActionRequest(String toolId, String targetRef, Long targetRevision, String value, String dataClass, Integer scrollDeltaY, String waitCondition, Integer timeoutMs) {}

  public record AgentInstructionSource(String sourceId, String sourceType, String classification, String content) {}

  public record AgentTaskListResponse(List<AgentTask> items, Long total, Integer limit, Integer offset) {}

  public record AgentTaskSummaryListResponse(List<AgentTaskSummary> items, AgentTaskSummaryMetrics metrics, Long total, Integer limit, Object nextCursor, Boolean hasMore) {}

  public record AgentTaskSummaryMetrics(Long planned, Long completed, Long blocked) {}

  public record AgentTaskSummary(String taskId, String sessionId, String goal, String state, String riskClass, String intentDecision, Object blockedReason, String agentPolicy, Integer currentStep, Integer totalSteps, Integer securityEventCount, Object executionWaitReason, Object executionWaitSince, String createdAt, String updatedAt) {}

  public record AgentTask(String taskId, String sessionId, String goal, String state, AgentRiskClass riskClass, String intentDecision, Object blockedReason, AgentPolicy agentPolicy, Integer currentStep, Integer totalSteps, Integer replanCount, AgentStepExecution stepExecution, AgentExecutionWait executionWait, AgentConfirmation confirmation, AgentHumanHandoff humanHandoff, AgentReview review, List<String> allowedDomains, AgentPlan plan, Object operationId, List<AgentToolExecutionResult> executionResults, Object lastError, List<PromptSecurityEvent> securityEvents, String createdAt, String updatedAt) {}

  public record ClaimAgentExecutionJobRequest(String protocolVersion, Map<String, Boolean> capabilities) {}

  public record AgentExecutionJobClaimRequest(String claimToken) {}

  public record FailAgentExecutionJobRequest(String claimToken, String failureCode, Boolean retryable) {}

  public record AgentExecutionJob(String jobId, String taskId, String protocolVersion, String state, Integer attempt, Integer maximumAttempts, Object workerId, Long claimEpoch, Object leaseExpiresAt, String availableAt, Object startedAt, Object completedAt, Object failureCode, String updatedAt) {}

  public record AgentExecutionJobClaim(String claimToken, AgentExecutionJob job, String leaseExpiresAt, Long claimEpoch) {}

  public record ClaimAgentReviewJobRequest(String protocolVersion, Map<String, Boolean> capabilities, String deploymentId, String modelRevision) {}

  public record AgentReviewJobClaimRequest(String claimToken) {}

  public record CompleteAgentReviewJobRequest(String claimToken, String decision, List<String> reasonCodes, Double confidence, String deploymentId, String modelRevision, Object providerRequestId, Integer inputTokens, Integer outputTokens, Integer latencyMs, String outputHash) {}

  public record FailAgentReviewJobRequest(String claimToken, String failureCode, Boolean retryable) {}

  public record AgentReviewStep(String stepId, String toolId, AgentRiskClass riskClass, Object targetOrigin, Object targetRefHash, Object dataClass, Object payloadLength, Boolean requiredConfirmation, String strategy, String requiredStateQuality, String verification) {}

  public record AgentReviewPayload(String taskId, String goal, AgentRiskClass riskClass, List<String> allowedDomains, Integer maximumActions, Integer replanBudget, List<AgentReviewStep> steps, String planHash, String dataPolicy) {}

  public record ReviewerModelDeployment(String deploymentId, String providerType, String modelName, String modelRevision, String dataPolicy, Integer maximumOutputTokens) {}

  public record AgentReviewJob(String jobId, String reviewId, String taskId, String protocolVersion, String state, Integer attempt, Integer maximumAttempts, Object workerId, Long claimEpoch, Object leaseExpiresAt, String availableAt, ReviewerModelDeployment deployment, Object decision, List<String> reasonCodes, Object confidence, String inputHash, Object outputHash, Object providerRequestId, Object inputTokens, Object outputTokens, Object costMicros, Object latencyMs, Object startedAt, Object completedAt, Object failureCode, String updatedAt) {}

  public record AgentReviewJobClaim(String claimToken, AgentReviewJob job, AgentReviewPayload reviewPayload, String leaseExpiresAt, Long claimEpoch) {}

  public record AgentReview(Object reviewId, String status, Object decision, List<String> reasonCodes, Object planHash, Object deploymentId, Object modelName, Object modelRevision, Object inputTokens, Object outputTokens, Object costMicros, Object latencyMs, Object failureCode, Object completedAt) {}

  public record AgentStepExecution(Object pendingStepId, Object pendingToolId, Object baseStateVersion, Object baseContentHash, Object deadline, Object leaseUntil, Object replanReason) {}

  public record AgentExecutionWait(Object reason, Object since) {}

  public record AgentConfirmation(Object confirmationId, Object status, Object expiresAt, Object decidedAt, Object actorId, Object evidenceHash) {}

  public record AgentHumanHandoff(Object requestId, Object status, Object expiresAt, Object actorId) {}

  public record AgentPlan(String intentId, List<AgentPlanStep> steps, Integer maxActions, Integer replanBudget, String expiresAt) {}

  public record AgentPlanStep(String stepId, String toolId, AgentRiskClass riskClass, Object targetUrl, Object input, String rationale, List<String> supportingSources, String trustFloor, List<String> taintLabels, Boolean requiredConfirmation, String strategy, String requiredStateQuality, String verification, String capabilityTokenId) {}

  public record AgentStepInput(Object targetRef, Object targetRevision, Object payloadHash, Object payloadLength, Object dataClass, Object scrollDeltaY, Object waitCondition, Object timeoutMs) {}

  public enum AgentRiskClass { R0READONLY, R1LOWRISKCHANGE, R2DATACHANGE, R3ACCOUNTCHANGE, R4FINANCIAL, R5SECURITY }

  public enum AgentPolicy { DISABLED, RESTRICTED, BALANCED, INTERACTIVE }

  public record AgentToolExecutionResult(String stepId, String toolId, String status, String resultHash, Map<String, Object> output, String verification, String completedAt) {}

  public record PromptSecurityEvent(String eventId, String eventType, String severity, String decision, String ruleCode, String sourceType, String contentHash, String createdAt) {}

  public record CreateProfileRequest(String profileId, String name, String description) {}

  public record Profile(String profileId, String tenantId, String name, Object description, Object latestCheckpointId, Object latestCheckpointEpoch, Long profileWriteEpoch, Long coreSizeBytes, Long checkpointFileCount, String restoreStatus, String state, String createdAt, String updatedAt, Object lastCheckpointAt) {}

  public record ProfileListResponse(List<Profile> items, Integer total) {}

  public record ProfileImport(String importId, String operationId, String profileId, String profileName, String runtimeBuildId, String archiveSha256, Long archiveSizeBytes, String state, Object nodeId, String checkpointId, Object checkpointEpoch, Object profileWriteEpoch, Object coreSizeBytes, Object checkpointFileCount, Object errorCode, String requestId, String createdAt, String updatedAt, Object completedAt) {}

  public record ProfileImportListResponse(List<ProfileImport> items, Integer total) {}

  public record ProxyProvider(String providerId, String type, String endpoint, String expectedExitIp, Boolean directFallbackAllowed, String state, List<String> regions, Double costPerGibUsd, Integer reputationScore, Integer maxConcurrentSessions) {}

  public record ProxyAllocation(String allocationId, String sessionId, String providerId, String protocol, String state, Object exitIp, Object country, Object asn, String allocatedAt, Object verifiedAt, Object releasedAt, String updatedAt) {}

  public record ProxyOverview(ProxyProvider provider, List<ProxyProvider> providers, List<ProxyAllocation> allocations, Integer total) {}

  public enum ProxyBindingHealth { UNVERIFIED, HEALTHY, UNHEALTHY, DISABLED }

  public record ProxyBindingRequest(String name, Object description, String providerId, Object region, String expectedExitIp, Object credentialRef, Boolean enabled, Object expectedVersion) {}

  public record ProxyBinding(String bindingProfileId, String name, Object description, String providerId, Object region, String expectedExitIp, Boolean credentialConfigured, Boolean enabled, ProxyBindingHealth healthState, Object lastVerifiedExitIp, Object lastHealthCheckedAt, Object lastFailureReason, Long probeSampleCount, Object probeSuccessRatePercent, Object latencyEwmaMs, Object qualityScore, Double costPerGibUsd, Integer reputationScore, Integer maxConcurrentSessions, Boolean automaticRoutingReady, Object healthFreshUntil, Integer consecutiveFailures, Long version, String createdBy, String createdAt, String updatedAt) {}

  public record ProxyBindingList(List<ProxyBinding> items, Integer total) {}

  public record ProxyRoutingCandidateScore(String bindingProfileId, String providerId, Double routingScore, Integer qualityScore, Integer reputationScore, Double costPerGibUsd, Double costScore, Double regionScore, Double headroomScore, Integer activeReservations, Integer maxConcurrentSessions) {}

  public record ProxyRoutingDecision(String sessionId, String bindingProfileId, String providerId, String selectionMode, Object routingScore, Object qualityScore, Object reputationScore, Object costPerGibUsd, Object activeReservations, Object maxConcurrentSessions, Integer candidateCount, List<ProxyRoutingCandidateScore> candidateScores, String selectedAt) {}

  public record ProxyRebindRequest(String targetBindingProfileId, String reason) {}

  public record ProxyRebindOperation(String workflowId, String operationId, String phase, String createdAt) {}

  public record ProxyRebind(String workflowId, String sessionId, Object sourceBindingProfileId, String targetBindingProfileId, Long targetBindingVersion, Object hibernateOperationId, Object restoreOperationId, Object resyncRequestId, String phase, Object recoveryResult, Object failureReason, String requestedBy, String reason, String requestId, String createdAt, String updatedAt, Object completedAt) {}

  public enum SessionState { CREATED, STARTING, RUNNING, DEGRADED, HIBERNATING, HIBERNATED, RECOVERING, TERMINATING, TERMINATED, FAILED }

  public enum ResourceTemplate { SuspendedV1, StandardLiteV1, StandardV1, InteractiveV1, HeavyV1, NativeStandardV1 }

  public record RegisterBrowserNodeRequest(String region, String grpcTarget, Integer certifiedCpuMillis, Integer certifiedMemoryMib, Integer certifiedPidCount, Integer certifiedGpuSlots, Integer certifiedMediaSlots, Integer safetyMarginPercent, Integer maxSessions, Boolean supportsDesktop, Boolean supportsGpu, Boolean supportsMedia, Boolean supportsNativeOs, Boolean isolationCapable, Map<String, String> labels) {}

  public record RecordNodePressureRequest(Double memoryPsiSomeAvg10, Double memoryPsiFullAvg10, Double cpuPsiSomeAvg10, Double ioPsiFullAvg10, Object reason) {}

  public record BrowserNode(String nodeId, String region, String grpcTarget, String lifecycleState, String admissionState, Integer certifiedCpuMillis, Integer certifiedMemoryMib, Integer certifiedPidCount, Integer certifiedGpuSlots, Integer certifiedMediaSlots, Integer safetyMarginPercent, Integer reservedCpuMillis, Integer reservedMemoryMib, Integer reservedPidCount, Integer reservedGpuSlots, Integer reservedMediaSlots, Integer activeSessions, Integer maxSessions, Double memoryPsiSomeAvg10, Double memoryPsiFullAvg10, Double cpuPsiSomeAvg10, Double ioPsiFullAvg10, String pressureState, Object pressureReason, Boolean supportsDesktop, Boolean supportsGpu, Boolean supportsMedia, Boolean supportsNativeOs, Boolean isolationCapable, Map<String, String> labels, String lastHeartbeatAt, String updatedAt) {}

  public record BrowserNodeListResponse(List<BrowserNode> items, Integer total) {}

  public record UpsertExtensionProfileRequest(String displayName, Integer staticCpuWeight, Integer staticMemoryWeight, Integer startupWeight, Integer pageInjectionWeight, Integer serviceWorkerWeight, Integer cryptoWeight, Integer networkWeight, Double observedMultiplier, Double confidence, String profileState, Boolean web3, Boolean serviceWorker, Boolean crypto, Boolean privileged) {}

  public record ExtensionProfile(String extensionId, String displayName, Integer staticCpuWeight, Integer staticMemoryWeight, Integer startupWeight, Integer pageInjectionWeight, Integer serviceWorkerWeight, Integer cryptoWeight, Integer networkWeight, Double observedMultiplier, Double confidence, String profileState, Boolean web3, Boolean serviceWorker, Boolean crypto, Boolean privileged, Long samples, Object p95CpuMillis, Object p95MemoryMib, Object lastProfiledAt, String samplingTier, Integer samplingCpuBudgetMillis, Object nextSampleAt, String updatedAt) {}

  public record RecordExtensionSampleRequest(String nodeId, Integer cpuMillis, Integer memoryMib, Boolean cgroupPsiBurst, Integer sampleCpuMillis, String observedAt) {}

  public record ExtensionProfileListResponse(List<ExtensionProfile> items, Integer total) {}

  public record BrowserPlacement(String sessionId, String tenantId, String nodeId, ResourceTemplate requestedTemplate, ResourceTemplate resolvedTemplate, List<String> extensionIds, Integer unknownExtensionCount, Integer cpuMillis, Integer memoryRequestMib, Integer memoryLimitMib, Integer pidLimit, Integer tabBudget, Integer stateCollectorBudgetPercent, Integer remoteDesktopBitrateKbps, Integer extensionCpuWeight, Boolean requiresDesktop, Boolean requiresGpu, Boolean requiresNativeOs, Boolean requiresIsolation, Boolean requiresMedia, Integer mediaSlots, Integer mediaEncoderSlots, Boolean backgroundTabsFrozen, Boolean newTabsBlocked, List<String> pausedExtensionIds, Integer successTraceSamplePercent, Integer successScreenshotSamplePercent, Integer observerFrameRateFps, Boolean videoRecordingRequested, Boolean videoRecordingEnabled, Integer mediaBitrateKbps, Integer placementScore, String state, List<String> reasonCodes, String reservedAt, Object activatedAt, Object releasedAt) {}

  public record BrowserState(String sessionId, Long contextEpoch, Long stateVersion, Long targetRevision, String url, String title, String stateHash, String stateQuality, String documentReadyState, Long networkQuietMillis, Boolean networkEvidenceFresh, List<InteractiveTarget> targets) {}

  public record StateResyncRequest(String mode, Object rootRef, Object reason) {}

  public record StateResyncResponse(String requestId, String mode, String state) {}

  public record InteractiveTarget(String targetRef, String role, Object name, Object bounds, Boolean enabled, Boolean visible) {}

  public record TargetBounds(Double x, Double y, Double width, Double height) {}

  public record RecoveryTargetIndicator(String role, String name) {}

  public record ProviderEvidenceRequirement(String type, String key, String providerId, String expectedValueHash, Integer maxAgeSeconds) {}

  public record UpsertRecoveryContractRequest(Long expectedVersion, List<String> expectedOrigins, List<String> readyRoutePrefixes, List<String> loginRoutePrefixes, List<RecoveryTargetIndicator> requiredTargets, List<RecoveryTargetIndicator> loginTargets, List<RecoveryTargetIndicator> permissionDeniedTargets, List<RecoveryTargetIndicator> accountMismatchTargets, List<String> requiredExtensionIds, List<ProviderEvidenceRequirement> requiredProviderEvidence, Boolean requireDocumentComplete, Integer minimumNetworkQuietMillis, List<RecoveryTargetIndicator> transientBlockerTargets, Boolean allowDepthLimited, String recoveryAction, Object recoveryExtensionId, Integer maximumAutoRecovery, Boolean enabled) {}

  public record RecoveryContract(String contractId, String applicationId, Long version, List<String> expectedOrigins, List<String> readyRoutePrefixes, List<String> loginRoutePrefixes, List<RecoveryTargetIndicator> requiredTargets, List<RecoveryTargetIndicator> loginTargets, List<RecoveryTargetIndicator> permissionDeniedTargets, List<RecoveryTargetIndicator> accountMismatchTargets, List<String> requiredExtensionIds, List<ProviderEvidenceRequirement> requiredProviderEvidence, Boolean requireDocumentComplete, Integer minimumNetworkQuietMillis, List<RecoveryTargetIndicator> transientBlockerTargets, Boolean allowDepthLimited, String recoveryAction, Object recoveryExtensionId, Integer maximumAutoRecovery, Boolean enabled, String approvalState, Object approvalId, Object approvalRequestedBy, Object approvedBy, Object approvalRequestedAt, Object approvalDecidedAt, String createdAt, String updatedAt) {}

  public record RecoveryContractListResponse(List<RecoveryContract> items, Long total) {}

  public record RecoveryContractRevisionListResponse(List<RecoveryContract> items, Long total, Long currentVersion) {}

  public record RecoveryContractFieldChange(String field, String changeType, String beforeValue, String afterValue) {}

  public record RecoveryContractDiff(String contractId, String applicationId, Long fromVersion, Long toVersion, List<RecoveryContractFieldChange> changes, Long total) {}

  public record RestoreRecoveryContractRevisionRequest(Long expectedCurrentVersion, Long sourceContractVersion, String reason) {}

  public record RequestRecoveryContractApprovalRequest(Long expectedVersion, String reason) {}

  public record RecoveryContractApproval(String approvalId, String contractId, String applicationId, Long contractVersion, String reason, String state, String requestedBy, Object approvedBy, Object rejectedBy, String requestedAt, Object decidedAt, Object evidenceHash) {}

  public record SessionApplicationBinding(String sessionId, String applicationId, String contractId, Long contractVersion, Long latestContractVersion, String latestApprovalState, Boolean currentContractEnabled, Boolean upgradeAvailable, String boundAt) {}

  public record RebindSessionApplicationRequest(Long expectedCurrentVersion, Long targetContractVersion) {}

  public record SessionApplicationRebind(String operationId, String sessionId, String applicationId, String contractId, Long previousContractVersion, Long targetContractVersion, String state, String requestId, String createdAt, String completedAt) {}

  public record BusinessRecoveryValidation(String validationId, String sessionId, Object applicationId, Object contractVersion, Long contextEpoch, Long stateVersion, String verdict, Boolean ready, List<String> evidence, String source, String requestId, String evaluatedAt) {}

  public record SubmitProviderEvidenceRequest(Long contextEpoch, Long stateVersion, String type, String key, String providerId, String observedValueHash, String outcome, String providerReference, String observedAt) {}

  public record ProviderEvidence(String evidenceId, String sessionId, String applicationId, Long contractVersion, Long contextEpoch, Long stateVersion, String type, String key, String providerId, String outcome, Boolean valueHashMatched, String providerReferenceHash, String adapterActorId, String requestId, String observedAt, String expiresAt, String createdAt) {}

  public record ProviderEvidenceListResponse(List<ProviderEvidence> items, Long total) {}

  public record CreateSessionRequest(String tenantId, String profileId, String runtimeBuildId, String applicationId, String groupId, List<String> tagIds, String region, String proxyBindingProfileId, ResourcePolicyRequest resourcePolicy, Integer requestedTabs, Integer agentActionsPerMinute, Boolean remoteDesktop, Boolean humanTakeoverEnabled, AgentPolicy agentPolicy, Boolean web3Workload, Boolean mediaWorkload, Integer requestedMediaStreams, Integer mediaBitrateKbps, Boolean videoRecording, List<String> extensionIds, Map<String, String> metadata) {}

  public record CreateSessionResponse(String sessionId, Object operationId, String state, ResourcePolicy resourcePolicy, SessionContext context) {}

  public record ResourcePolicyRequest(String mode, MaximumReachedPolicy onMaximumReached, Boolean allowMigration, Boolean allowHibernate, Boolean blockMigrationDuringHumanTakeover, ExecutionEnvironment executionEnvironment, String minimumTemplate, Integer maximumCpuMillis, Integer maximumMemoryMib, Double maximumCostPerHour, Integer scaleUpWindowSeconds, Integer scaleDownWindowSeconds, Integer adjustmentCooldownSeconds) {}

  public record ResourcePolicy(String mode, MaximumReachedPolicy onMaximumReached, Boolean allowMigration, Boolean allowHibernate, Boolean blockMigrationDuringHumanTakeover, ExecutionEnvironment executionEnvironment, String minimumTemplate, Integer maximumCpuMillis, Integer maximumMemoryMib, Double maximumCostPerHour, Integer scaleUpWindowSeconds, Integer scaleDownWindowSeconds, Integer adjustmentCooldownSeconds, String resolvedTemplate) {}

  public enum ExecutionEnvironment { SYSTEMMANAGED, CONTAINER, ENHANCEDSANDBOX, MICROVM, NATIVEOS }

  public enum MaximumReachedPolicy { PAUSEAGENT, WAITSAFEPOINTMIGRATE, HIBERNATE, TERMINATESTRICT }

  public enum ResourcePolicyStatus { STABLE, OBSERVING, SCALINGUP, SCALINGDOWN, ATMAXIMUM, WAITINGSAFEPOINT, MIGRATING, AGENTPAUSED, HIBERNATING, CRITICAL }

  public record SessionResource(String sessionId, ResourcePolicy policy, Object allocation, Object usage, List<Map<String, Object>> usageSamples, Object cost, ResourcePolicyStatus status, Object statusReason, String dataFreshness, Object lastEvaluatedAt, Object lastAdjustedAt) {}

  public record ResourceEventList(List<Map<String, Object>> items, Integer limit, Integer offset) {}

  public record Evidence(String evidenceId, String evidenceKind, String taskId, String stepId, String commandId, Boolean mandatory, String result, Object contentSha256, Long contentBytes, String capturedAt, Object errorCode, String redactionState, Integer redactedRegionCount) {}

  public record EvidenceList(List<Evidence> items, Integer limit, Integer offset) {}

  public enum EvidencePurpose { INCIDENTRESPONSE, CHANGEVALIDATION, SUPPORTDIAGNOSTICS, COMPLIANCEAUDIT }

  public record CaptureEvidenceRequest(EvidencePurpose purpose) {}

  public record EvidenceCapture(String captureId, String sessionId, EvidencePurpose purpose, String state, Object evidenceId, Object errorCode, String commandId, Object requestId, String createdAt, Object completedAt) {}

  public record CreateEvidenceAccessGrantRequest(EvidencePurpose purpose) {}

  public record EvidenceAccessGrant(String grantId, String sessionId, String evidenceId, EvidencePurpose purpose, String state, String expiresAt, String createdAt, Object redeemedAt, Object errorCode, Object requestId) {}

  public record RedeemEvidenceAccessResponse(String grantId, String evidenceId, String downloadUrl, String expiresAt) {}

  public record SessionSafePoint(String sessionId, Boolean safe, String state, String dataFreshness, Object nodeId, Long contextEpoch, String evaluatedAt, Object lastNodeObservationAt, List<SafePointBlocker> blockers) {}

  public record SafePointBlocker(String code, String source, String detail, Object observedAt, Object expiresAt) {}

  public record CreateSafetyLeaseRequest(String signalType, String reasonCode, Integer ttlSeconds) {}

  public record RenewSafetyLeaseRequest(Integer ttlSeconds) {}

  public record SafetyLease(String leaseId, String sessionId, Long contextEpoch, String signalType, String reasonCode, String ownerActorId, String state, String acquiredAt, String renewedAt, String expiresAt, Object releasedAt) {}

  public record SafetyLeaseList(List<SafetyLease> items, Integer total) {}

  public record SessionMigration(String migrationId, String sessionId, String sourceNodeId, Object targetNodeId, Long sourceContextEpoch, Object targetContextEpoch, Object checkpointId, Object hibernateOperationId, Object restoreOperationId, Object targetCleanupOperationId, Integer targetAttempt, Integer maximumTargetAttempts, List<String> failedTargetNodeIds, Object lastTargetFailureReason, Object resyncRequestId, String phase, Object recoveryResult, Object failureReason, Integer autoRecoveryAttempts, Integer autoRecoveryMaximum, Object latestRecoveryAction, String createdAt, String updatedAt, Object completedAt) {}

  public record BusinessRecoveryAction(String actionId, String migrationId, Integer attemptNumber, String action, Object targetUrl, Object targetExtensionId, Long baseStateVersion, Object resultingStateVersion, String state, Object errorCode, String createdAt, Object completedAt) {}

  public record ResourcePolicyOperation(String operationId, String state, ResourcePolicy resourcePolicy) {}

  public record SessionContext(String sessionId, String tenantId, String profileId, Object nodeId, Object runtimeBuildId, Object isolationProfileId, Object proxyBindingId, Long coordinatorTerm, Long contextEpoch, Long browserGeneration, Long networkRevision, ResourceTemplate resourceTemplate, SessionState state, String policyHash, String createdAt, String updatedAt) {}

  public record SessionView(String sessionId, String displayName, String tenantId, String profileId, Object groupId, List<WorkspaceTagSummary> tags, Boolean humanTakeoverEnabled, AgentPolicy agentPolicy, List<String> extensionIds, String region, ResourceTemplate resourceTemplate, SessionState state, Object nodeId, Object runtimeBuildId, Object proxyBindingId, Object proxyBindingProfileId, Object proxyRoutingDecision, Long contextEpoch, Long browserGeneration, Object currentOperation, String createdAt, String updatedAt) {}

  public enum EnvironmentSavedViewScope { PERSONAL, WORKSPACE }

  public enum EnvironmentPrimaryView { ALL, RUNNING, STOPPED, ABNORMAL }

  public enum EnvironmentSavedViewTagMatch { ANY, ALL }

  public record CreateEnvironmentSavedViewRequest(String name, EnvironmentSavedViewScope scope, EnvironmentPrimaryView primaryView, Object sessionState, String searchQuery, Object groupId, List<String> tagIds, Object tagMatch, Boolean showRuntimeColumn, Boolean showContextColumn, Boolean showOperationColumn) {}

  public record UpdateEnvironmentSavedViewRequest(Long expectedVersion, String name, EnvironmentPrimaryView primaryView, Object sessionState, String searchQuery, Object groupId, List<String> tagIds, Object tagMatch, Boolean showRuntimeColumn, Boolean showContextColumn, Boolean showOperationColumn) {}

  public record EnvironmentSavedView(String savedViewId, String name, EnvironmentSavedViewScope scope, String ownerActorId, EnvironmentPrimaryView primaryView, Object sessionState, String searchQuery, Object groupId, List<String> tagIds, EnvironmentSavedViewTagMatch tagMatch, Boolean showRuntimeColumn, Boolean showContextColumn, Boolean showOperationColumn, String createdAt, String updatedAt, Long version) {}

  public record EnvironmentSavedViewListResponse(List<EnvironmentSavedView> items, Integer total) {}

  public enum EnvironmentImportState { VALIDATED, INVALID, EXECUTING, COMMITTED }

  public enum EnvironmentImportValidationState { READY, INVALID }

  public enum EnvironmentImportExecutionState { PENDING, SUCCEEDED }

  public record EnvironmentImportSpec(String displayName, Object description, String profileId, Object runtimeBuildId, Object applicationId, Object groupId, Object tagIds, Object region, Object resourcePolicy, Integer requestedTabs, Integer agentActionsPerMinute, Boolean remoteDesktop, Object humanTakeoverEnabled, Object agentPolicy, Boolean web3Workload, Boolean mediaWorkload, Integer requestedMediaStreams, Integer mediaBitrateKbps, Boolean videoRecording, Object extensionIds) {}

  public record PreviewEnvironmentImportRequest(Integer schemaVersion, String name, List<EnvironmentImportSpec> environments) {}

  public record CommitEnvironmentImportRequest(Long expectedVersion) {}

  public record EnvironmentImportItem(String itemId, Integer itemIndex, EnvironmentImportSpec specification, EnvironmentImportValidationState validationState, List<String> validationErrors, EnvironmentImportExecutionState executionState, Object sessionId, Object operationId, Object requestId, String updatedAt) {}

  public record EnvironmentImport(String importId, String name, Integer schemaVersion, String manifestHash, EnvironmentImportState state, Integer totalCount, Integer readyCount, Integer succeededCount, List<EnvironmentImportItem> items, String createdAt, String updatedAt, Object committedAt, Long version) {}

  public record EnvironmentImportListItem(String importId, String name, EnvironmentImportState state, Integer totalCount, Integer readyCount, Integer succeededCount, String createdAt, String updatedAt, Long version) {}

  public record EnvironmentImportListResponse(List<EnvironmentImportListItem> items, Integer total) {}

  public record WorkspaceGroupRequest(String name, Object description, String color, MaximumReachedPolicy defaultOnMaximumReached, Boolean defaultAllowMigration, Boolean defaultAllowHibernate) {}

  public record WorkspaceGroupSession(String sessionId, String displayName, SessionState state, String region, String updatedAt) {}

  public record WorkspaceGroup(String groupId, String name, Object description, String color, MaximumReachedPolicy defaultOnMaximumReached, Boolean defaultAllowMigration, Boolean defaultAllowHibernate, List<WorkspaceGroupSession> sessions, Integer sessionCount, String createdBy, String createdAt, String updatedAt) {}

  public record WorkspaceGroupListResponse(List<WorkspaceGroup> items, List<WorkspaceGroupSession> unassignedSessions, Integer total) {}

  public record WorkspaceTagRequest(String name, Object description, String color) {}

  public record WorkspaceTagSummary(String tagId, String name, String color) {}

  public record WorkspaceTagSession(String sessionId, String displayName, SessionState state, String region, String updatedAt) {}

  public record WorkspaceTag(String tagId, String name, Object description, String color, List<WorkspaceTagSession> sessions, Integer sessionCount, String createdBy, String createdAt, String updatedAt) {}

  public record WorkspaceTagListResponse(List<WorkspaceTag> items, List<WorkspaceTagSession> sessions, Integer total) {}

  public enum WorkspaceBatchAction { START, PAUSEAGENT, MIGRATE, HIBERNATE }

  public enum WorkspaceBatchState { ACCEPTED, EXECUTING, CANCELLING, SUCCEEDED, PARTIALSUCCESS, FAILED, CANCELLED }

  public enum WorkspaceBatchItemState { ACCEPTED, EXECUTING, SUCCEEDED, FAILED, CANCELLED }

  public record WorkspaceBatchSelector(Object groupId, List<String> tagIds, String tagMatch, List<String> sessionIds) {}

  public record CreateWorkspaceBatchOperationRequest(WorkspaceBatchAction action, WorkspaceBatchSelector selector, Object reason, Boolean confirmed) {}

  public record CancelWorkspaceBatchOperationRequest(String reason) {}

  public record WorkspaceBatchOperationItem(String batchItemId, String sessionId, Integer ordinal, String commandId, WorkspaceBatchItemState state, Object childOperationId, Object failureCode, String createdAt, Object startedAt, Object completedAt) {}

  public record WorkspaceBatchOperation(String batchOperationId, WorkspaceBatchAction action, WorkspaceBatchState state, WorkspaceBatchSelector selector, Object reason, Integer total, Integer accepted, Integer executing, Integer succeeded, Integer failed, Integer cancelled, Boolean cancellationRequested, List<WorkspaceBatchOperationItem> items, String actorId, String createdAt, String updatedAt) {}

  public record WorkspaceBatchOperationListResponse(List<WorkspaceBatchOperation> items, Integer total) {}

  public enum WorkspaceMetadataBatchAction { ASSIGNGROUP, REMOVEGROUP, ASSIGNTAGS, REMOVETAGS }

  public record WorkspaceMetadataBatchSelector(Object groupId, List<String> tagIds, String tagMatch, List<String> sessionIds) {}

  public record WorkspaceMetadataBatchTarget(Object groupId, List<String> tagIds) {}

  public record CreateWorkspaceMetadataBatchOperationRequest(WorkspaceMetadataBatchAction action, WorkspaceMetadataBatchSelector selector, WorkspaceMetadataBatchTarget target, String reason, Boolean confirmed) {}

  public record WorkspaceMetadataBatchOperationItem(String batchItemId, String sessionId, Integer ordinal, WorkspaceBatchItemState state, Object failureCode, Integer attempt, String createdAt, Object startedAt, Object completedAt) {}

  public record WorkspaceMetadataBatchOperation(String batchOperationId, WorkspaceMetadataBatchAction action, WorkspaceBatchState state, WorkspaceMetadataBatchSelector selector, WorkspaceMetadataBatchTarget target, String reason, Integer total, Integer accepted, Integer executing, Integer succeeded, Integer failed, Integer cancelled, Boolean cancellationRequested, List<WorkspaceMetadataBatchOperationItem> items, String actorId, String createdAt, String updatedAt) {}

  public record WorkspaceMetadataBatchOperationListResponse(List<WorkspaceMetadataBatchOperation> items, Integer total) {}

  public record WorkspaceSettingsRequest(String workspaceName, String defaultRuntimeBuildId, String defaultRegion, Boolean defaultHumanTakeoverEnabled) {}

  public record WorkspaceSettings(String workspaceName, String defaultRuntimeBuildId, String defaultRegion, Boolean defaultHumanTakeoverEnabled, String resourcePolicyMode, String onMaximumReached, String source, Object updatedBy, Object updatedAt, Long version) {}

  public record SessionListResponse(List<SessionView> items, Integer total, Integer limit, Integer offset) {}

  public record OperationResponse(String operationId, String state) {}

  public record OperationView(String operationId, String ownerType, Object actorId, String mode, Integer priority, Long coordinatorTerm, Long contextEpoch, Long operationEpoch, Object workflowId, Boolean cancellable, Boolean preemptible, String phase, String state, List<String> allowedCapabilities, String deadline) {}

  public record RemoteDesktopConnection(String webSocketPath, String expiresAt, String protocol, Long operationEpoch, Boolean viewOnly) {}

  public record AuditEvent(String eventId, Long sequenceNo, Object sessionId, String eventType, String actorType, Object actorId, Object resourceType, Object resourceId, String action, String result, Map<String, Object> details, Object previousEventHash, String eventHash, Object requestId, String retentionUntil, Boolean legalHold, String createdAt) {}

  public record AuditEventListResponse(List<AuditEvent> items, Long total, Boolean chainValid, Object headHash) {}

  public record RuntimeBuild(String buildId, String engine, String version, String platform, String securityTier, String regressionStatus, String releaseChannel, Boolean signatureVerified, Object signature, Object artifactDigest, Object signingKeyId, Object sbomUrl, Object validatedAt, Object releasedAt, Object disabledAt, Object disabledBy, String createdAt) {}

  public record RuntimeBuildListResponse(List<RuntimeBuild> items, Integer total) {}

  public record CreateRuntimeReleaseRequest(String targetChannel, String reason) {}

  public record CreateRuntimeDisableRequest(String reason) {}

  public record RuntimeReleaseRequest(String releaseId, String buildId, String targetChannel, String reason, String state, String requestedBy, Object approvedBy, Object rejectedBy, String requestedAt, Object decidedAt, Object evidenceHash) {}

  public record RuntimeReleaseRequestListResponse(List<RuntimeReleaseRequest> items, Integer total) {}

  public record CreateKeyRotationRequest(String keyScope, String oldKeyId, String newKeyId, String rotationTrigger, String reason, Integer overlapMinutes) {}

  public record CompleteKeyRotationRequest(Boolean newKeyWriteVerified, Boolean oldKeyReadVerified, Boolean plaintextRejected, Integer affectedWorkloads, String verificationReference) {}

  public record KeyRotationRequest(String rotationId, String keyScope, String oldKeyId, String newKeyId, String rotationTrigger, String reason, Integer requestedOverlapMinutes, String state, String requestedBy, Object approvedBy, Object completedBy, Object revokedBy, String requestedAt, Object approvedAt, Object startedAt, Object completedAt, Object revokedAt, Object overlapUntil, Integer progressPercent, Object newKeyWriteVerified, Object oldKeyReadVerified, Object plaintextRejected, Object affectedWorkloads, Object verificationReference, Object approvalEvidenceHash, Object completionEvidenceHash) {}

  public record KeyRotationRequestListResponse(List<KeyRotationRequest> items, Integer total) {}

  public record CreateBreakGlassRequest(String ticketId, String reason, String resourceType, String resourceId, String requestedScope, Integer durationMinutes) {}

  public record BreakGlassRequest(String requestId, String ticketId, String reason, String resourceType, String resourceId, String requestedScope, String state, String requestedBy, Object approvedBy, Object rejectedBy, Object revokedBy, Object evidenceHash, String requestedAt, Object approvedAt, Object rejectedAt, Object revokedAt, String expiresAt, Object reviewedAt) {}

  public record BreakGlassRequestListResponse(List<BreakGlassRequest> items, Integer total) {}

  public record SecureDebugSession(String debugSessionId, String breakGlassRequestId, String resourceType, String resourceId, String operatorId, String state, String startedAt, String expiresAt, Object endedAt, Object endReason, Integer accessCount, Object lastAccessAt, Object evidenceHeadHash) {}

  public record SecureDebugSessionListResponse(List<SecureDebugSession> items, Integer total) {}

  public record SecureDebugSnapshot(String debugSessionId, String sessionId, String sessionState, Object runtimeBuildId, Integer contextEpoch, Integer browserGeneration, Integer networkRevision, Object urlOrigin, Integer stateVersion, Integer targetRevision, String stateQuality, Object stateHash, Integer interactiveTargetCount, Integer sensitiveTargetCount, String capturedAt, Integer accessCount, String accessEvidenceHash, String dataClassification, String fieldProjection) {}

  public record StartRuntimeValidationRequest(String buildId, String suiteVersion, String environmentDigest, String replayDatasetId, String persona, String browserEngine, String browserVersion, String operatingSystem, String architecture, BooleanMap requiredWorkerCapabilities, Integer maximumAttempts) {}

  public record RuntimeValidationMatrixCellRequest(String environmentDigest, String browserEngine, String browserVersion, String operatingSystem, String architecture, BooleanMap requiredWorkerCapabilities, Integer maximumAttempts) {}

  public record StartRuntimeValidationMatrixRequest(String buildId, String suiteVersion, String replayDatasetId, String persona, List<RuntimeValidationMatrixCellRequest> cells) {}

  public record CompleteRuntimeValidationRequest(Integer requiredTests, Integer requiredFailures, Integer optionalTests, Integer optionalFailures, BooleanMap declaredCapabilities, BooleanMap observedCapabilities, List<String> optionalFailureCodes, Boolean personaConsistent) {}

  public record RuntimeValidation(String validationId, String buildId, String suiteVersion, String environmentDigest, String replayDatasetId, String persona, String state, Integer requiredTests, Integer requiredFailures, Integer optionalTests, Integer optionalFailures, BooleanMap declaredCapabilities, BooleanMap observedCapabilities, List<String> optionalFailureCodes, Object evidenceHash, String requestedBy, String startedAt, Object completedAt, Object job) {}

  public record RuntimeValidationJob(String validationId, String browserEngine, String browserVersion, String operatingSystem, String architecture, BooleanMap requiredWorkerCapabilities, String state, Integer attempt, Integer maximumAttempts, Object workerId, Long claimEpoch, String availableAt, Object leaseExpiresAt, Object lastHeartbeatAt, Object failureCode, Object resultHash, String updatedAt) {}

  public record ClaimRuntimeValidationJobRequest(String browserEngine, List<String> browserVersions, String operatingSystem, String architecture, BooleanMap capabilities) {}

  public record RuntimeValidationJobClaimRequest(String claimToken) {}

  public record RuntimeValidationJobClaim(String claimToken, RuntimeValidation validation, String leaseExpiresAt, Long claimEpoch) {}

  public record CompleteRuntimeValidationJobRequest(String claimToken, CompleteRuntimeValidationRequest result) {}

  public record FailRuntimeValidationJobRequest(String claimToken, String failureCode, Boolean retryable) {}

  public record CreateCostRateRequest(String region, ResourceTemplate resourceTemplate, Double baseHourlyUsd, Double cpuCoreHourlyUsd, Double memoryGibHourlyUsd, Double desktopHourlyUsd, Double gpuHourlyUsd, Double mediaHourlyUsd, String effectiveAt) {}

  public record CostRate(String pricingVersion, String region, ResourceTemplate resourceTemplate, Double baseHourlyUsd, Double cpuCoreHourlyUsd, Double memoryGibHourlyUsd, Double desktopHourlyUsd, Double gpuHourlyUsd, Double mediaHourlyUsd, String effectiveAt, String createdBy, String createdAt) {}

  public record SessionCostExplanation(String sessionId, String nodeId, String region, ResourceTemplate resourceTemplate, String pricingVersion, Integer cpuMillis, Integer memoryRequestMib, Boolean desktop, Boolean gpu, Boolean media, Double baseHourlyUsd, Double cpuHourlyUsd, Double memoryHourlyUsd, Double desktopHourlyUsd, Double gpuHourlyUsd, Double mediaHourlyUsd, Double totalHourlyUsd, String pricedAt) {}

  public record UpsertMediaQuotaRequest(Integer maxConcurrentStreams, Integer maxBitrateKbps) {}

  public record MediaQuota(String tenantId, Integer maxConcurrentStreams, Integer maxBitrateKbps, Long activeStreams, Long activeBitrateKbps, String updatedBy, String updatedAt) {}

  public record UpsertSloPolicyRequest(Double availabilityTarget, Integer latencyP95TargetMs, Integer windowMinutes, Boolean releaseFreezeEnabled, Double releaseFreezeBurnRateThreshold, Double releaseRecoveryBurnRateThreshold, Integer releaseFreezeWindowMinutes, Integer releaseRecoveryStableMinutes) {}

  public record RecordServiceLevelEventRequest(String eventType, Integer durationSeconds, Object latencyP95Ms, String source, String occurredAt, Object exclusionCode) {}

  public record UpsertSlaExclusionRequest(String description, Boolean enabled) {}

  public record SlaExclusion(String tenantId, String exclusionCode, String description, Boolean enabled, String updatedBy, String updatedAt) {}

  public record ErrorBudget(String tenantId, Double availabilityTarget, Integer latencyP95TargetMs, Integer windowMinutes, Long allowedUnavailableSeconds, Long consumedUnavailableSeconds, Long remainingUnavailableSeconds, Double burnRatio, String state, String windowStartedAt, String calculatedAt) {}

  public record ReleaseFreeze(String tenantId, Boolean enabled, String phase, Boolean frozen, Double currentBurnRate, Double freezeBurnRateThreshold, Double recoveryBurnRateThreshold, Integer evaluationWindowMinutes, Integer recoveryStableMinutes, String reasonCode, Object stableSince, Object frozenAt, Object clearedAt, String evaluatedAt, Long version) {}

  public record UpsertRetentionPolicyRequest(String dataClass, Integer retentionDays, Boolean legalHold, String residencyRegion) {}

  public record RetentionPolicy(String tenantId, String dataClass, Integer retentionDays, Boolean legalHold, String residencyRegion, String updatedBy, String updatedAt) {}

  public record CreateDeletionReceiptRequest(String dataClass, String objectId, String contentDigest) {}

  public record DeletionReceipt(String receiptId, String tenantId, String dataClass, String objectId, String contentDigest, String policyUpdatedAt, String receiptHash, String deletedBy, String deletedAt) {}

  public record UpsertLicenseInventoryRequest(String componentType, String componentName, String componentVersion, String licenseId, String sourceUrl, Boolean approved) {}

  public record LicenseInventory(String componentId, String componentType, String componentName, String componentVersion, String licenseId, String sourceUrl, Boolean approved, String evidenceHash, String updatedBy, String updatedAt) {}

  public record AuditExportManifest(String exportId, String tenantId, Long fromSequence, Long toSequence, Long eventCount, String firstEventHash, String lastEventHash, String manifestHash, String signatureAlgorithm, String signingKeyId, String signature, String generatedBy, String generatedAt) {}

  public record UpsertRegionRequest(String role, String admissionState, Integer replicationLagSeconds) {}

  public record EnterpriseRegion(String regionId, String role, String admissionState, Integer replicationLagSeconds, String lastVerifiedAt, String updatedBy) {}

  public record StartRecoveryGameDayRequest(String scenario, String sourceRegion, String targetRegion, Integer rtoTargetSeconds, Integer rpoTargetSeconds, String executionMode, String environment, RecoveryGameDayBlastRadius blastRadius, Integer maximumDurationSeconds, String approvalRequestId, Map<String, Boolean> requiredWorkerCapabilities, Integer maximumAttempts) {}

  public record RecoveryGameDayBlastRadius(String scope, Integer maximumTargets, List<String> targetIds) {}

  public record CompleteRecoveryGameDayRequest(Integer observedRtoSeconds, Integer observedRpoSeconds, Integer dataLossRecords, Integer detectionTimeSeconds, Integer failoverTimeSeconds, Integer staleOperationCount, Integer userImpactCount, Integer manualSteps, Integer runbookAccuracyPercent, String runnerEvidenceHash, Boolean recoveryConfirmed) {}

  public record ClaimRecoveryGameDayJobRequest(List<String> environments, List<String> scenarioCodes, Map<String, Boolean> capabilities) {}

  public record RecoveryGameDayJobClaimRequest(String claimToken) {}

  public record UpdateRecoveryGameDayStageRequest(String claimToken, String stage) {}

  public record CompleteRecoveryGameDayJobRequest(String claimToken, CompleteRecoveryGameDayRequest result) {}

  public record FailRecoveryGameDayJobRequest(String claimToken, String failureCode, Boolean retryable, Boolean recoveryConfirmed) {}

  public record RecoveryGameDayJob(String gameDayId, String scenarioCode, String environment, Map<String, Boolean> requiredWorkerCapabilities, String state, String currentStage, Integer attempt, Integer maximumAttempts, Integer recoveryAttempt, Integer maximumRecoveryAttempts, Object workerId, Long claimEpoch, String availableAt, Object leaseExpiresAt, Object lastHeartbeatAt, String abortDeadline, Boolean abortRequested, Boolean faultInjected, Object recoveryConfirmed, Object failureCode, Object resultHash, String updatedAt) {}

  public record RecoveryGameDayJobClaim(String claimToken, RecoveryGameDay gameDay, String leaseExpiresAt, Long claimEpoch, Boolean recoveryOnly) {}

  public record RecoveryGameDay(String gameDayId, String scenario, String sourceRegion, String targetRegion, String state, Integer rtoTargetSeconds, Integer rpoTargetSeconds, Object observedRtoSeconds, Object observedRpoSeconds, Object dataLossRecords, Object evidenceHash, String startedBy, String startedAt, Object completedAt, String executionMode, String environment, Object blastRadius, Integer maximumDurationSeconds, Object approvalRequestId, String currentStage, Boolean abortRequested, Object recoveryConfirmed, Object failureCode, Object job) {}

  public record RecoveryGameDayEvent(String eventId, String gameDayId, String eventType, Object fromState, String toState, String stage, Object workerId, Long claimEpoch, Integer attempt, Object reasonCode, String occurredAt) {}

  public record RecoveryGameDayEventPage(List<RecoveryGameDayEvent> items, Object nextCursor, Boolean hasMore) {}

  public record RecoveryGameDayTrend(String scenario, String environment, Long totalRuns, Long passedRuns, Long failedRuns, Long abortedRuns, Long recoveryUnknownRuns, Double passRatePercent, Object p95RtoSeconds, Object p95RpoSeconds, Long openTicketCount, String latestRunAt) {}

  public record RecoveryGameDayReportExport(String exportId, String gameDayId, String reportFormat, Integer eventCount, Map<String, Object> report, String reportHash, String signatureAlgorithm, String signingKeyId, String signature, String generatedBy, String generatedAt) {}

  public record UpdateRecoveryGameDayRemediationRequest(String state, String ownerId, String resolution) {}

  public record RecoveryGameDayRemediation(String ticketId, String gameDayId, String scenario, String environment, String severity, String state, String reasonCode, String summary, Object ownerId, Object resolution, String createdBy, String createdAt, String updatedBy, String updatedAt, Object resolvedAt) {}

  public record ComplianceSnapshot(String snapshotId, String tenantId, String framework, Integer controlCount, Integer passingControls, String evidenceHash, BooleanMap evidence, String generatedBy, String generatedAt) {}

  public record EnterpriseOverview(List<RuntimeValidation> validations, List<CostRate> costRates, Object mediaQuota, Object errorBudget, Object releaseFreeze, List<SlaExclusion> slaExclusions, List<RetentionPolicy> retentionPolicies, List<LicenseInventory> licenseInventory, List<EnterpriseRegion> regions, List<RecoveryGameDay> recoveryGameDays, List<RecoveryGameDayTrend> recoveryGameDayTrends, List<RecoveryGameDayRemediation> recoveryGameDayRemediations, Object latestCompliance, String generatedAt) {}

  public record BooleanMap(Map<String, Object> values) {}

  public record Error(String code, String message, Map<String, Object> details, String requestId, String timestamp) {}

}
