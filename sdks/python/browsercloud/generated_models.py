"""Generated OpenAPI models. Do not edit."""

from __future__ import annotations

from typing import Any, Literal, TypedDict

GENERATOR = "browsercloud-multilang-generator@1"

class WorkspaceOverview(TypedDict, total=False):
    sessions: WorkspaceSessionSummary
    operations: WorkspaceOperationSummary
    browserNodes: WorkspaceBrowserNodeSummary
    proxies: WorkspaceProxySummary
    agents: WorkspaceAgentSummary
    cost: WorkspaceCostSummary
    security: WorkspaceSecuritySummary
    cursor: int
    generatedAt: str

class WorkspaceSessionSummary(TypedDict, total=False):
    total: int
    running: int
    pending: int
    unhealthy: int
    hibernated: int
    terminated: int

class WorkspaceOperationSummary(TypedDict, total=False):
    active: int

class WorkspaceBrowserNodeSummary(TypedDict, total=False):
    visible: bool
    total: int
    ready: int
    constrained: int
    activeSessions: int
    maximumSessions: int
    reservedCpuMillis: int
    certifiedCpuMillis: int
    reservedMemoryMib: int
    certifiedMemoryMib: int

class WorkspaceProxySummary(TypedDict, total=False):
    activeAllocations: int
    boundSessions: int

class WorkspaceAgentSummary(TypedDict, total=False):
    active: int
    awaitingHuman: int
    pausedByResourcePolicy: int
    failedLast24Hours: int

class WorkspaceCostSummary(TypedDict, total=False):
    currentHourlyUsd: float
    activeSessionsWithoutCurrentPrice: int

class WorkspaceSecuritySummary(TypedDict, total=False):
    warningLast24Hours: int
    criticalLast24Hours: int

SearchResourceType = Literal['SESSION', 'PROFILE', 'GROUP', 'TAG', 'RUNTIME', 'NODE']

class GlobalSearchResult(TypedDict, total=False):
    resourceType: SearchResourceType
    resourceId: str
    title: str
    description: Any
    status: Any
    region: Any
    updatedAt: Any

class GlobalSearchResponse(TypedDict, total=False):
    query: str
    items: list[GlobalSearchResult]
    limit: int
    truncated: bool

NotificationCategory = Literal['SECURITY', 'RESOURCE', 'AGENT', 'RELEASE', 'SYSTEM']

NotificationSeverity = Literal['INFO', 'WARNING', 'CRITICAL']

class WorkspaceNotification(TypedDict, total=False):
    notificationId: str
    sequence: int
    category: NotificationCategory
    severity: NotificationSeverity
    title: str
    body: str
    eventType: str
    sessionId: Any
    resourceType: Any
    resourceId: Any
    requestId: Any
    route: str
    read: bool
    occurredAt: str

class WorkspaceNotificationListResponse(TypedDict, total=False):
    items: list[WorkspaceNotification]
    unreadCount: int
    lastReadSequence: int
    headSequence: int
    nextBeforeSequence: Any

class UpdateNotificationReadCursorRequest(TypedDict, total=False):
    readThroughSequence: int

class WorkspaceNotificationReadState(TypedDict, total=False):
    lastReadSequence: int
    unreadCount: int
    updatedAt: str

ThemeMode = Literal['SYSTEM', 'DARK', 'LIGHT']

class UpdateUserPreferencesRequest(TypedDict, total=False):
    themeMode: ThemeMode

class UserPreferences(TypedDict, total=False):
    themeMode: ThemeMode
    source: Literal['SYSTEM_DEFAULT', 'USER_OVERRIDE']
    updatedAt: Any
    version: int

class TenantRoute(TypedDict, total=False):
    tenantId: str
    state: Literal['STABLE', 'MIGRATING']
    activeVirtualPartitions: int
    activeRouteEpoch: int
    pendingVirtualPartitions: Any
    pendingRouteEpoch: Any
    activeMigrationId: Any
    version: int
    updatedAt: str

class RequestTenantRouteMigration(TypedDict, total=False):
    expectedRouteEpoch: int
    targetVirtualPartitions: int

class TenantRouteMigration(TypedDict, total=False):
    migrationId: str
    tenantId: str
    sourceRouteEpoch: int
    targetRouteEpoch: int
    sourceVirtualPartitions: int
    targetVirtualPartitions: int
    state: Literal['MIGRATING', 'COMMITTED', 'FAILED']
    totalSessions: int
    migratedSessions: int
    blockedSessions: int
    requestedBy: str
    requestId: str
    failureCode: Any
    createdAt: str
    updatedAt: str
    completedAt: Any

class CreateAgentTaskRequest(TypedDict, total=False):
    goal: str
    startUrl: str
    allowedDomains: list[str]
    maxActions: int
    replanBudget: int
    contextSources: list[AgentInstructionSource]
    actions: list[AgentActionRequest]

class AgentActionRequest(TypedDict, total=False):
    toolId: Literal['CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR', 'EXECUTE_ACTIONS', 'REQUEST_HUMAN_TAKEOVER']
    targetRef: str
    targetRevision: int
    value: str
    secretId: str
    dataClass: Literal['PUBLIC', 'PII', 'CREDENTIAL', 'OTP']
    scrollDeltaY: int
    waitCondition: Literal['STATE_CHANGED', 'STATE_STABLE', 'TARGET_PRESENT']
    timeoutMs: int
    actions: list[AgentBatchActionRequest]
    stopOnError: bool

class AgentBatchActionRequest(TypedDict, total=False):
    toolId: Literal['CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR']
    targetRef: str
    targetRevision: int
    value: str
    secretId: str
    dataClass: Literal['PUBLIC', 'PII', 'CREDENTIAL', 'OTP']
    scrollDeltaY: int
    waitCondition: Literal['STATE_CHANGED', 'STATE_STABLE', 'TARGET_PRESENT']
    timeoutMs: int

class AgentInstructionSource(TypedDict, total=False):
    sourceId: str
    sourceType: Literal['APPLICATION_DATA', 'EMAIL', 'DOCUMENT', 'WEB_CONTENT', 'THIRD_PARTY_WIDGET']
    classification: str
    content: str

class AgentTaskListResponse(TypedDict, total=False):
    items: list[AgentTask]
    total: int
    limit: int
    offset: int

class AgentTaskSummaryListResponse(TypedDict, total=False):
    items: list[AgentTaskSummary]
    metrics: AgentTaskSummaryMetrics
    total: int
    limit: int
    nextCursor: Any
    hasMore: bool

class AgentTaskSummaryMetrics(TypedDict, total=False):
    planned: int
    completed: int
    blocked: int

class AgentTaskSummary(TypedDict, total=False):
    taskId: str
    sessionId: str
    goal: str
    state: Literal['PLANNED', 'QUEUED', 'AWAITING_REVIEW', 'AWAITING_CONFIRMATION', 'BLOCKED', 'RUNNING', 'WAITING_FOR_HUMAN', 'PAUSED_BY_RESOURCE_POLICY', 'COMPLETED', 'FAILED']
    riskClass: Literal['R0_READ_ONLY', 'R1_LOW_RISK_CHANGE', 'R2_DATA_CHANGE', 'R3_ACCOUNT_CHANGE', 'R4_FINANCIAL', 'R5_SECURITY']
    intentDecision: Literal['ALLOWED', 'CONFIRM_REQUIRED', 'FORBIDDEN']
    blockedReason: Any
    agentPolicy: Literal['DISABLED', 'RESTRICTED', 'BALANCED', 'INTERACTIVE']
    currentStep: int
    totalSteps: int
    securityEventCount: int
    executionWaitReason: Literal['HUMAN_INPUT_PRIORITY', None]
    executionWaitSince: Any
    createdAt: str
    updatedAt: str

class ChallengeAutomationPolicy(TypedDict, total=False):
    sessionId: str
    controlMode: Literal['SAFE', 'AUTONOMOUS']
    sensitiveInputMaximumAttempts: int
    enabled: bool
    maximumAttempts: int
    minimumConfidence: float
    allowMultiClick: bool
    allowSlide: bool
    motionMinimumSteps: int
    motionMaximumSteps: int
    motionMinimumDelayMs: int
    motionMaximumDelayMs: int
    targetOffsetRatio: float
    updatedAt: str

class UpdateChallengeAutomationPolicyRequest(TypedDict, total=False):
    controlMode: Literal['SAFE', 'AUTONOMOUS']
    sensitiveInputMaximumAttempts: int
    enabled: bool
    maximumAttempts: int
    minimumConfidence: float
    allowMultiClick: bool
    allowSlide: bool
    motionMinimumSteps: int
    motionMaximumSteps: int
    motionMinimumDelayMs: int
    motionMaximumDelayMs: int
    targetOffsetRatio: float

class CreateAgentInputSecretRequest(TypedDict, total=False):
    purpose: Literal['USERNAME', 'PASSWORD', 'OTP']
    value: str
    expiresAt: str

class AgentInputSecret(TypedDict, total=False):
    secretId: str
    sessionId: str
    purpose: Literal['USERNAME', 'PASSWORD', 'OTP']
    expiresAt: str
    consumed: bool

class ChallengeAutomationRun(TypedDict, total=False):
    runId: str
    challengeEventId: str
    state: Literal['CAPTURING', 'ANALYZING', 'EXECUTING', 'COMPLETED', 'EXHAUSTED', 'ESCALATED', 'FAILED']
    attemptCount: int
    maximumAttempts: int
    lastAction: Any
    lastErrorCode: Any
    updatedAt: str
    completedAt: Any

class ClaimChallengeVisualJobRequest(TypedDict, total=False):
    protocolVersion: str
    capabilities: dict[str, bool]
    deploymentId: str
    modelRevision: str

class ChallengeVisualJobClaimRequest(TypedDict, total=False):
    claimToken: str

class ChallengeVisualAction(TypedDict, total=False):
    actionType: Literal['CLICK', 'SLIDE']
    x: float
    y: float
    endX: Any
    endY: Any
    repeatCount: int

class CompleteChallengeVisualJobRequest(TypedDict, total=False):
    claimToken: str
    decision: Literal['ACT', 'ESCALATE']
    actions: list[ChallengeVisualAction]
    confidence: float
    deploymentId: str
    modelRevision: str
    providerRequestId: Any
    inputTokens: int
    outputTokens: int
    latencyMs: int
    outputHash: str

class FailChallengeVisualJobRequest(TypedDict, total=False):
    claimToken: str
    failureCode: str
    retryable: bool

class ChallengeVisualJob(TypedDict, total=False):
    jobId: str
    runId: str
    challengeEventId: str
    state: Literal['CAPTURING', 'READY', 'CLAIMED', 'RUNNING', 'EXECUTING', 'COMPLETED', 'FAILED', 'ESCALATED']
    attemptNumber: int
    maximumAttempts: int
    workerId: Any
    claimEpoch: int
    leaseExpiresAt: Any
    decision: Literal['ACT', 'ESCALATE', None]
    actions: list[ChallengeVisualAction]
    confidence: Any
    failureCode: Any
    updatedAt: str

class ChallengeVisualJobClaim(TypedDict, total=False):
    claimToken: str
    job: ChallengeVisualJob
    screenshotUrl: str
    screenshotExpiresAt: str
    challengeType: Literal['SINGLE_CLICK', 'IMAGE_SELECTION', 'PUZZLE', 'MULTI_ROUND']
    targetSummary: str
    allowMultiClick: bool
    allowSlide: bool
    minimumConfidence: float

class ChallengeRegion(TypedDict, total=False):
    x: float
    y: float
    width: float
    height: float

class ChallengeEvent(TypedDict, total=False):
    challengeEventId: str
    sessionId: str
    contextEpoch: int
    stateVersion: int
    targetRevision: int
    confidence: float
    evidence: dict[str, Any]
    suspectedType: Literal['SINGLE_CLICK', 'IMAGE_SELECTION', 'PUZZLE', 'OTP', 'DEVICE_CONFIRMATION', 'MULTI_ROUND', 'USER_JUDGMENT', 'PAYMENT_CONFIRMATION', 'UNKNOWN']
    accessOutcome: Literal['CHALLENGE_SUSPECTED', 'CHALLENGE_CONFIRMED']
    targetRef: Any
    targetSummary: str
    status: Literal['SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'RESOLVED', 'FAILED', 'EXPIRED', 'SUPERSEDED', 'TAKEOVER_REQUIRED']
    oneClickEligible: bool
    detectedAt: str
    authorizationDeadline: str
    expiresAt: str
    updatedAt: str

class ChallengeEventListResponse(TypedDict, total=False):
    items: list[ChallengeEvent]

class ChallengePreview(TypedDict, total=False):
    challenge: ChallengeEvent
    previewHash: str
    highlight: ChallengeRegion | None
    fresh: bool
    canAuthorize: bool
    blockingReason: Any
    previewedAt: str

class AuthorizeHumanAssistRequest(TypedDict, total=False):
    previewHash: str
    expectedStateVersion: int
    expectedTargetRevision: int

class HumanAssistIntent(TypedDict, total=False):
    intentId: str
    challengeEventId: str
    sessionId: str
    userId: str
    contextEpoch: int
    stateVersion: int
    targetRevision: int
    allowedTargetRef: str
    allowedActionCount: int
    consumedCount: int
    authorizationEventId: str
    operationId: Any
    requestId: str
    state: Literal['AUTHORIZED', 'EXECUTING', 'COMMITTED', 'FAILED', 'EXPIRED']
    expiresAt: str
    createdAt: str
    consumedAt: Any
    completedAt: Any
    errorCode: Any

class SubmitChallengeInputResponseRequest(TypedDict, total=False):
    secretId: str

class ChallengeInputResponse(TypedDict, total=False):
    intentId: str
    challengeEventId: str
    sessionId: str
    taskId: str
    purpose: str
    state: Literal['EXECUTING', 'COMMITTED', 'FAILED', 'EXPIRED']
    maximumAttempts: int
    operationId: str
    expiresAt: str
    createdAt: str
    completedAt: Any
    errorCode: Any

class AgentTask(TypedDict, total=False):
    taskId: str
    sessionId: str
    goal: str
    state: Literal['PLANNED', 'QUEUED', 'AWAITING_REVIEW', 'AWAITING_CONFIRMATION', 'BLOCKED', 'RUNNING', 'WAITING_FOR_HUMAN', 'PAUSED_BY_RESOURCE_POLICY', 'COMPLETED', 'FAILED']
    riskClass: AgentRiskClass
    intentDecision: Literal['ALLOWED', 'CONFIRM_REQUIRED', 'FORBIDDEN']
    blockedReason: Any
    agentPolicy: AgentPolicy
    currentStep: int
    totalSteps: int
    replanCount: int
    stepExecution: AgentStepExecution
    executionWait: AgentExecutionWait
    confirmation: AgentConfirmation
    humanHandoff: AgentHumanHandoff
    challengeEventId: Any
    review: AgentReview
    allowedDomains: list[str]
    plan: AgentPlan
    operationId: Any
    executionResults: list[AgentToolExecutionResult]
    lastError: Any
    securityEvents: list[PromptSecurityEvent]
    createdAt: str
    updatedAt: str

class ClaimAgentExecutionJobRequest(TypedDict, total=False):
    protocolVersion: str
    capabilities: dict[str, bool]

class AgentExecutionJobClaimRequest(TypedDict, total=False):
    claimToken: str

class FailAgentExecutionJobRequest(TypedDict, total=False):
    claimToken: str
    failureCode: str
    retryable: bool

class AgentExecutionJob(TypedDict, total=False):
    jobId: str
    taskId: str
    protocolVersion: str
    state: Literal['QUEUED', 'CLAIMED', 'EXECUTING', 'WAITING', 'COMMITTED', 'FAILED']
    attempt: int
    maximumAttempts: int
    workerId: Any
    claimEpoch: int
    leaseExpiresAt: Any
    availableAt: str
    startedAt: Any
    completedAt: Any
    failureCode: Any
    updatedAt: str

class AgentExecutionJobClaim(TypedDict, total=False):
    claimToken: str
    job: AgentExecutionJob
    leaseExpiresAt: str
    claimEpoch: int

class ClaimAgentReviewJobRequest(TypedDict, total=False):
    protocolVersion: str
    capabilities: dict[str, bool]
    deploymentId: str
    modelRevision: str

class AgentReviewJobClaimRequest(TypedDict, total=False):
    claimToken: str

class CompleteAgentReviewJobRequest(TypedDict, total=False):
    claimToken: str
    decision: Literal['APPROVE', 'REJECT']
    reasonCodes: list[Literal['SAFE', 'EXCESSIVE_SCOPE', 'DOMAIN_MISMATCH', 'RISK_UNDERCLASSIFIED', 'MISSING_CONFIRMATION', 'UNSUPPORTED_TOOL', 'DATA_POLICY_VIOLATION', 'PROMPT_INJECTION_RISK', 'MODEL_UNCERTAIN']]
    confidence: float
    deploymentId: str
    modelRevision: str
    providerRequestId: Any
    inputTokens: int
    outputTokens: int
    latencyMs: int
    outputHash: str

class FailAgentReviewJobRequest(TypedDict, total=False):
    claimToken: str
    failureCode: str
    retryable: bool

class AgentReviewStep(TypedDict, total=False):
    stepId: str
    toolId: Literal['NAVIGATE', 'GET_CURRENT_STATE', 'CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR', 'EXECUTE_ACTIONS', 'GET_URL', 'GET_PAGE_SUMMARY', 'REQUEST_HUMAN_TAKEOVER']
    riskClass: AgentRiskClass
    targetOrigin: Any
    targetRefHash: Any
    dataClass: Literal['PUBLIC', 'PII', None]
    payloadLength: Any
    batchActionCount: int
    batchActionHash: Any
    requiredConfirmation: bool
    strategy: Literal['SEMANTIC_DOM', 'ACCESSIBILITY', 'DESKTOP_INPUT', 'VISION_DESKTOP', 'HUMAN_ASSIST', 'HUMAN_TAKEOVER']
    requiredStateQuality: str
    verification: str

class AgentReviewPayload(TypedDict, total=False):
    taskId: str
    goal: str
    riskClass: AgentRiskClass
    allowedDomains: list[str]
    maximumActions: int
    replanBudget: int
    steps: list[AgentReviewStep]
    planHash: str
    dataPolicy: str

class ReviewerModelDeployment(TypedDict, total=False):
    deploymentId: str
    providerType: Literal['OPENAI_RESPONSES']
    modelName: str
    modelRevision: str
    dataPolicy: str
    maximumOutputTokens: int

class AgentReviewJob(TypedDict, total=False):
    jobId: str
    reviewId: str
    taskId: str
    protocolVersion: str
    state: Literal['QUEUED', 'CLAIMED', 'EXECUTING', 'APPROVED', 'REJECTED', 'FAILED']
    attempt: int
    maximumAttempts: int
    workerId: Any
    claimEpoch: int
    leaseExpiresAt: Any
    availableAt: str
    deployment: ReviewerModelDeployment
    decision: Literal['APPROVE', 'REJECT', None]
    reasonCodes: list[str]
    confidence: Any
    inputHash: str
    outputHash: Any
    providerRequestId: Any
    inputTokens: Any
    outputTokens: Any
    costMicros: Any
    latencyMs: Any
    startedAt: Any
    completedAt: Any
    failureCode: Any
    updatedAt: str

class AgentReviewJobClaim(TypedDict, total=False):
    claimToken: str
    job: AgentReviewJob
    reviewPayload: AgentReviewPayload
    leaseExpiresAt: str
    claimEpoch: int

class AgentReview(TypedDict, total=False):
    reviewId: Any
    status: Literal['NOT_REQUIRED', 'PENDING', 'QUEUED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'FAILED']
    decision: Literal['APPROVE', 'REJECT', None]
    reasonCodes: list[str]
    planHash: Any
    deploymentId: Any
    modelName: Any
    modelRevision: Any
    inputTokens: Any
    outputTokens: Any
    costMicros: Any
    latencyMs: Any
    failureCode: Any
    completedAt: Any

class AgentStepExecution(TypedDict, total=False):
    pendingStepId: Any
    pendingToolId: Any
    baseStateVersion: Any
    baseContentHash: Any
    deadline: Any
    leaseUntil: Any
    replanReason: Any

class AgentExecutionWait(TypedDict, total=False):
    reason: Literal['HUMAN_INPUT_PRIORITY', None]
    since: Any

class AgentConfirmation(TypedDict, total=False):
    confirmationId: Any
    status: Any
    expiresAt: Any
    decidedAt: Any
    actorId: Any
    evidenceHash: Any

class AgentHumanHandoff(TypedDict, total=False):
    requestId: Any
    status: Any
    expiresAt: Any
    actorId: Any

class AgentPlan(TypedDict, total=False):
    intentId: str
    steps: list[AgentPlanStep]
    maxActions: int
    replanBudget: int
    expiresAt: str

class AgentPlanStep(TypedDict, total=False):
    stepId: str
    toolId: Literal['NAVIGATE', 'GET_CURRENT_STATE', 'CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR', 'EXECUTE_ACTIONS', 'GET_URL', 'GET_PAGE_SUMMARY', 'REQUEST_HUMAN_TAKEOVER']
    riskClass: AgentRiskClass
    targetUrl: Any
    input: AgentStepInput | None
    rationale: str
    supportingSources: list[str]
    trustFloor: Literal['TRUSTED', 'RESTRICTED', 'UNTRUSTED']
    taintLabels: list[str]
    requiredConfirmation: bool
    strategy: Literal['SEMANTIC_DOM', 'ACCESSIBILITY', 'DESKTOP_INPUT', 'VISION_DESKTOP', 'HUMAN_ASSIST', 'HUMAN_TAKEOVER']
    requiredStateQuality: str
    verification: str
    capabilityTokenId: str

class AgentStepInput(TypedDict, total=False):
    targetRef: Any
    targetRevision: Any
    payloadHash: Any
    payloadLength: Any
    dataClass: Any
    scrollDeltaY: Any
    waitCondition: Any
    timeoutMs: Any
    sensitiveTargetAuthorized: bool
    maximumAttempts: int
    actions: list[AgentBatchActionInput]
    stopOnError: bool

class AgentBatchActionInput(TypedDict, total=False):
    actionId: str
    toolId: Literal['CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR']
    targetRef: Any
    elementId: Any
    targetRevision: Any
    payloadHash: Any
    payloadLength: Any
    dataClass: Any
    scrollDeltaY: Any
    waitCondition: Any
    timeoutMs: Any
    sensitiveTargetAuthorized: bool
    maximumAttempts: int

AgentRiskClass = Literal['R0_READ_ONLY', 'R1_LOW_RISK_CHANGE', 'R2_DATA_CHANGE', 'R3_ACCOUNT_CHANGE', 'R4_FINANCIAL', 'R5_SECURITY']

AgentPolicy = Literal['DISABLED', 'RESTRICTED', 'BALANCED', 'INTERACTIVE']

class AgentToolExecutionResult(TypedDict, total=False):
    stepId: str
    toolId: Literal['NAVIGATE', 'GET_CURRENT_STATE', 'CLICK_TARGET', 'DOUBLE_CLICK_TARGET', 'RIGHT_CLICK_TARGET', 'HOVER_TARGET', 'CLEAR_TARGET', 'CHECK_TARGET', 'UNCHECK_TARGET', 'TYPE_TEXT', 'FILL', 'PASTE_AGENT_CLIPBOARD', 'SCROLL', 'WAIT_FOR', 'EXECUTE_ACTIONS', 'GET_URL', 'GET_PAGE_SUMMARY', 'REQUEST_HUMAN_TAKEOVER']
    status: Literal['VERIFIED', 'WAITING_FOR_HUMAN', 'ACCEPTED']
    resultHash: str
    output: dict[str, Any]
    verification: str
    completedAt: str

class PromptSecurityEvent(TypedDict, total=False):
    eventId: str
    eventType: str
    severity: str
    decision: str
    ruleCode: str
    sourceType: str
    contentHash: str
    createdAt: str

class CreateProfileRequest(TypedDict, total=False):
    profileId: str
    name: str
    description: str

class CreateProfileExportGrantRequest(TypedDict, total=False):
    purpose: ProfileExportPurpose

ProfileExportPurpose = Literal['INCIDENT_RESPONSE', 'SUPPORT_DIAGNOSTICS', 'COMPLIANCE_EXPORT', 'TENANT_BACKUP']

class ProfileExportGrant(TypedDict, total=False):
    grantId: str
    profileId: str
    checkpointId: str
    checkpointEpoch: int
    purpose: ProfileExportPurpose
    state: Literal['ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED']
    expiresAt: str
    createdAt: str
    redeemedAt: Any
    archiveSha256: Any
    archiveSizeBytes: Any
    errorCode: Any
    requestId: Any

class RedeemProfileExportResponse(TypedDict, total=False):
    grantId: str
    profileId: str
    checkpointId: str
    archiveSha256: str
    archiveSizeBytes: int
    downloadUrl: str
    expiresAt: str

class Profile(TypedDict, total=False):
    profileId: str
    tenantId: str
    name: str
    description: Any
    latestCheckpointId: Any
    latestCheckpointEpoch: Any
    profileWriteEpoch: int
    coreSizeBytes: int
    checkpointFileCount: int
    restoreStatus: Literal['EMPTY', 'TECHNICAL_READY']
    state: Literal['ACTIVE']
    createdAt: str
    updatedAt: str
    lastCheckpointAt: Any

class ProfileListResponse(TypedDict, total=False):
    items: list[Profile]
    total: int

class ProfileWarmTierStatus(TypedDict, total=False):
    state: Literal['AWAITING_FIRST_SYNC', 'LIVE']
    nodeId: Any
    profileWriteEpoch: Any
    journalSequence: Any
    transactionBarrier: Any
    changedFileCount: Any
    deletedFileCount: Any
    reusedChunkCount: Any
    uploadedBytes: Any
    deferredGroupCount: Any
    manifestSha256: Any
    committedAt: Any

class ProfileImport(TypedDict, total=False):
    importId: str
    operationId: str
    profileId: str
    profileName: str
    runtimeBuildId: str
    archiveSha256: str
    archiveSizeBytes: int
    state: Literal['REQUESTED', 'UPLOADING', 'VALIDATING', 'COMMITTED', 'FAILED']
    nodeId: Any
    checkpointId: str
    checkpointEpoch: Any
    profileWriteEpoch: Any
    coreSizeBytes: Any
    checkpointFileCount: Any
    errorCode: Any
    requestId: str
    createdAt: str
    updatedAt: str
    completedAt: Any

class ProfileImportListResponse(TypedDict, total=False):
    items: list[ProfileImport]
    total: int

class ProxyProvider(TypedDict, total=False):
    providerId: str
    type: Literal['STATIC_HTTP', 'STATIC_HTTP_CATALOG']
    endpoint: str
    expectedExitIp: str
    directFallbackAllowed: bool
    state: Literal['CONFIGURED', 'CATALOG_CONFIGURED', 'UNCONFIGURED']
    regions: list[str]
    costPerGibUsd: float
    reputationScore: int
    maxConcurrentSessions: int

class ProxyAllocation(TypedDict, total=False):
    allocationId: str
    sessionId: str
    providerId: str
    protocol: Literal['HTTP']
    state: Literal['ALLOCATED', 'BOUND', 'RELEASED', 'FAILED']
    exitIp: Any
    country: Any
    asn: Any
    allocatedAt: str
    verifiedAt: Any
    releasedAt: Any
    updatedAt: str

class ProxyOverview(TypedDict, total=False):
    provider: ProxyProvider
    providers: list[ProxyProvider]
    allocations: list[ProxyAllocation]
    total: int

ProxyBindingHealth = Literal['UNVERIFIED', 'HEALTHY', 'UNHEALTHY', 'DISABLED']

class ProxyBindingRequest(TypedDict, total=False):
    name: str
    description: Any
    providerId: str
    region: Any
    expectedExitIp: str
    credentialRef: Any
    enabled: bool
    expectedVersion: Any

class ProxyBinding(TypedDict, total=False):
    bindingProfileId: str
    name: str
    description: Any
    providerId: str
    region: Any
    expectedExitIp: str
    credentialConfigured: bool
    enabled: bool
    healthState: ProxyBindingHealth
    lastVerifiedExitIp: Any
    lastHealthCheckedAt: Any
    lastFailureReason: Any
    probeSampleCount: int
    probeSuccessRatePercent: Any
    latencyEwmaMs: Any
    qualityScore: Any
    costPerGibUsd: float
    reputationScore: int
    maxConcurrentSessions: int
    automaticRoutingReady: bool
    healthFreshUntil: Any
    consecutiveFailures: int
    version: int
    createdBy: str
    createdAt: str
    updatedAt: str

class ProxyBindingList(TypedDict, total=False):
    items: list[ProxyBinding]
    total: int

class ProxyRoutingCandidateScore(TypedDict, total=False):
    bindingProfileId: str
    providerId: str
    routingScore: float
    qualityScore: int
    reputationScore: int
    costPerGibUsd: float
    costScore: float
    regionScore: float
    headroomScore: float
    activeReservations: int
    maxConcurrentSessions: int

class ProxyRoutingDecision(TypedDict, total=False):
    sessionId: str
    bindingProfileId: str
    providerId: str
    selectionMode: Literal['EXPLICIT', 'AUTO']
    routingScore: Any
    qualityScore: Any
    reputationScore: Any
    costPerGibUsd: Any
    activeReservations: Any
    maxConcurrentSessions: Any
    candidateCount: int
    candidateScores: list[ProxyRoutingCandidateScore]
    selectedAt: str

class ProxyRebindRequest(TypedDict, total=False):
    targetBindingProfileId: str
    reason: str

class ProxyRebindOperation(TypedDict, total=False):
    workflowId: str
    operationId: str
    phase: Literal['CHECKPOINTING', 'PLACING_TARGET', 'RESTORING', 'TARGET_CLEANUP', 'STATE_RESYNC', 'BUSINESS_VALIDATION', 'BUSINESS_RECOVERY_ACTION', 'COMPLETED', 'DEGRADED', 'FAILED']
    createdAt: str

class ProxyRebind(TypedDict, total=False):
    workflowId: str
    sessionId: str
    sourceBindingProfileId: Any
    targetBindingProfileId: str
    targetBindingVersion: int
    hibernateOperationId: Any
    restoreOperationId: Any
    resyncRequestId: Any
    phase: Literal['CHECKPOINTING', 'PLACING_TARGET', 'RESTORING', 'TARGET_CLEANUP', 'STATE_RESYNC', 'BUSINESS_VALIDATION', 'BUSINESS_RECOVERY_ACTION', 'COMPLETED', 'DEGRADED', 'FAILED']
    recoveryResult: Any
    failureReason: Any
    requestedBy: str
    reason: str
    requestId: str
    createdAt: str
    updatedAt: str
    completedAt: Any

SessionState = Literal['CREATED', 'STARTING', 'RUNNING', 'DEGRADED', 'HIBERNATING', 'HIBERNATED', 'RECOVERING', 'TERMINATING', 'TERMINATED', 'FAILED']

ResourceTemplate = Literal['suspended-v1', 'standard-lite-v1', 'standard-v1', 'interactive-v1', 'heavy-v1', 'native-standard-v1']

class RegisterBrowserNodeRequest(TypedDict, total=False):
    region: str
    grpcTarget: str
    certifiedCpuMillis: int
    certifiedMemoryMib: int
    certifiedPidCount: int
    certifiedGpuSlots: int
    certifiedMediaSlots: int
    safetyMarginPercent: int
    maxSessions: int
    supportsDesktop: bool
    supportsGpu: bool
    supportsMedia: bool
    supportsNativeOs: bool
    isolationCapable: bool
    labels: dict[str, str]

class RecordNodePressureRequest(TypedDict, total=False):
    memoryPsiSomeAvg10: float
    memoryPsiFullAvg10: float
    cpuPsiSomeAvg10: float
    ioPsiFullAvg10: float
    reason: Any

class BrowserNode(TypedDict, total=False):
    nodeId: str
    region: str
    grpcTarget: str
    lifecycleState: Literal['READY', 'DRAINING', 'OFFLINE']
    admissionState: Literal['OPEN', 'CLOSED']
    certifiedCpuMillis: int
    certifiedMemoryMib: int
    certifiedPidCount: int
    certifiedGpuSlots: int
    certifiedMediaSlots: int
    safetyMarginPercent: int
    reservedCpuMillis: int
    reservedMemoryMib: int
    reservedPidCount: int
    reservedGpuSlots: int
    reservedMediaSlots: int
    activeSessions: int
    maxSessions: int
    memoryPsiSomeAvg10: float
    memoryPsiFullAvg10: float
    cpuPsiSomeAvg10: float
    ioPsiFullAvg10: float
    pressureState: Literal['NORMAL', 'DEGRADED', 'CRITICAL']
    pressureReason: Any
    supportsDesktop: bool
    supportsGpu: bool
    supportsMedia: bool
    supportsNativeOs: bool
    isolationCapable: bool
    labels: dict[str, str]
    lastHeartbeatAt: str
    updatedAt: str

class BrowserNodeListResponse(TypedDict, total=False):
    items: list[BrowserNode]
    total: int

class UpsertExtensionProfileRequest(TypedDict, total=False):
    displayName: str
    staticCpuWeight: int
    staticMemoryWeight: int
    startupWeight: int
    pageInjectionWeight: int
    serviceWorkerWeight: int
    cryptoWeight: int
    networkWeight: int
    observedMultiplier: float
    confidence: float
    profileState: Literal['PROBATION', 'OBSERVED', 'CERTIFIED', 'DISABLED']
    web3: bool
    serviceWorker: bool
    crypto: bool
    privileged: bool

class ExtensionProfile(TypedDict, total=False):
    extensionId: str
    displayName: str
    staticCpuWeight: int
    staticMemoryWeight: int
    startupWeight: int
    pageInjectionWeight: int
    serviceWorkerWeight: int
    cryptoWeight: int
    networkWeight: int
    observedMultiplier: float
    confidence: float
    profileState: Literal['PROBATION', 'OBSERVED', 'CERTIFIED', 'DISABLED']
    web3: bool
    serviceWorker: bool
    crypto: bool
    privileged: bool
    samples: int
    p95CpuMillis: Any
    p95MemoryMib: Any
    lastProfiledAt: Any
    samplingTier: Literal['LOW', 'MEDIUM', 'HIGH', 'DEEP']
    samplingCpuBudgetMillis: int
    nextSampleAt: Any
    updatedAt: str

class RecordExtensionSampleRequest(TypedDict, total=False):
    nodeId: str
    cpuMillis: int
    memoryMib: int
    cgroupPsiBurst: bool
    sampleCpuMillis: int
    observedAt: str

class ExtensionProfileListResponse(TypedDict, total=False):
    items: list[ExtensionProfile]
    total: int

class BrowserPlacement(TypedDict, total=False):
    sessionId: str
    tenantId: str
    nodeId: str
    requestedTemplate: ResourceTemplate
    resolvedTemplate: ResourceTemplate
    extensionIds: list[str]
    unknownExtensionCount: int
    cpuMillis: int
    memoryRequestMib: int
    memoryLimitMib: int
    pidLimit: int
    tabBudget: int
    stateCollectorBudgetPercent: int
    remoteDesktopBitrateKbps: int
    extensionCpuWeight: int
    requiresDesktop: bool
    requiresGpu: bool
    requiresNativeOs: bool
    requiresIsolation: bool
    requiresMedia: bool
    mediaSlots: int
    mediaEncoderSlots: int
    backgroundTabsFrozen: bool
    newTabsBlocked: bool
    pausedExtensionIds: list[str]
    successTraceSamplePercent: int
    successScreenshotSamplePercent: int
    observerFrameRateFps: int
    videoRecordingRequested: bool
    videoRecordingEnabled: bool
    mediaBitrateKbps: int
    placementScore: int
    state: Literal['RESERVED', 'ACTIVE', 'EVICTING', 'RELEASED']
    reasonCodes: list[str]
    reservedAt: str
    activatedAt: Any
    releasedAt: Any

class BrowserState(TypedDict, total=False):
    sessionId: str
    contextEpoch: int
    stateVersion: int
    targetRevision: int
    url: str
    title: str
    stateHash: str
    stateQuality: Literal['COMPLETE', 'DEPTH_LIMITED', 'RESYNCING', 'DEGRADED', 'INVALID']
    documentReadyState: Literal['loading', 'interactive', 'complete', '']
    networkQuietMillis: int
    networkEvidenceFresh: bool
    targets: list[InteractiveTarget]

class AgentBrowserSnapshot(TypedDict, total=False):
    stateCursor: str
    state: BrowserState
    visibleTextSummary: str
    activeTab: AgentBrowserTab
    focusedElementId: Any
    formControlElementIds: list[str]
    dialogElementIds: list[str]
    pageLoadingState: Literal['loading', 'interactive', 'complete', '']
    challengeState: Literal['NOT_EVALUATED']
    visionRecommended: bool

class AgentBrowserTab(TypedDict, total=False):
    url: str
    title: str
    active: bool

class AgentBrowserInspectRequest(TypedDict, total=False):
    stateCursor: str
    elementIds: list[str]

class AgentBrowserFindRequest(TypedDict, total=False):
    query: str
    roles: list[str]
    includeHidden: bool
    limit: Any

class AgentBrowserTargetList(TypedDict, total=False):
    stateCursor: str
    targets: list[InteractiveTarget]
    truncated: bool

class StateResyncRequest(TypedDict, total=False):
    mode: Literal['FULL', 'REGION']
    rootRef: Any
    reason: Any

class StateResyncResponse(TypedDict, total=False):
    requestId: str
    mode: Literal['FULL', 'REGION']
    state: Literal['QUEUED']

class InteractiveTarget(TypedDict, total=False):
    targetRef: str
    elementId: str
    role: str
    name: Any
    value: Any
    controlType: Any
    bounds: TargetBounds | None
    enabled: bool
    visible: bool
    sensitive: bool
    focused: bool
    checked: Any
    selected: Any
    interactive: bool
    frameId: str
    inViewport: bool
    occluded: bool
    visibilityReason: Literal['HIDDEN_ATTRIBUTE', 'ARIA_HIDDEN', 'DISPLAY_NONE', 'VISIBILITY_HIDDEN', 'OPACITY_ZERO', 'POINTER_EVENTS_NONE', 'COLLAPSED', 'ZERO_SIZE', 'OUTSIDE_VIEWPORT', 'OCCLUDED', 'LEGACY_VISIBILITY_UNKNOWN', None]

class TargetBounds(TypedDict, total=False):
    x: float
    y: float
    width: float
    height: float

class RecoveryTargetIndicator(TypedDict, total=False):
    role: str
    name: str

class ProviderEvidenceRequirement(TypedDict, total=False):
    type: Literal['ACCOUNT', 'TENANT_WORKSPACE', 'PERMISSION', 'BUSINESS_ENTITY']
    key: str
    providerId: str
    expectedValueHash: str
    maxAgeSeconds: int

class UpsertRecoveryContractRequest(TypedDict, total=False):
    expectedVersion: int
    expectedOrigins: list[str]
    readyRoutePrefixes: list[str]
    loginRoutePrefixes: list[str]
    requiredTargets: list[RecoveryTargetIndicator]
    loginTargets: list[RecoveryTargetIndicator]
    permissionDeniedTargets: list[RecoveryTargetIndicator]
    accountMismatchTargets: list[RecoveryTargetIndicator]
    requiredExtensionIds: list[str]
    requiredProviderEvidence: list[ProviderEvidenceRequirement]
    requireDocumentComplete: bool
    minimumNetworkQuietMillis: int
    transientBlockerTargets: list[RecoveryTargetIndicator]
    paymentSecurityRoutePrefixes: list[str]
    criticalTransactionRoutePrefixes: list[str]
    allowDepthLimited: bool
    recoveryAction: Literal['NONE', 'RELOAD', 'NAVIGATE_HOME', 'REOPEN_KNOWN_ROUTE', 'REFRESH_SESSION', 'RESTART_EXTENSION']
    recoveryExtensionId: Any
    maximumAutoRecovery: int
    enabled: bool

class RecoveryContract(TypedDict, total=False):
    contractId: str
    applicationId: str
    version: int
    expectedOrigins: list[str]
    readyRoutePrefixes: list[str]
    loginRoutePrefixes: list[str]
    requiredTargets: list[RecoveryTargetIndicator]
    loginTargets: list[RecoveryTargetIndicator]
    permissionDeniedTargets: list[RecoveryTargetIndicator]
    accountMismatchTargets: list[RecoveryTargetIndicator]
    requiredExtensionIds: list[str]
    requiredProviderEvidence: list[ProviderEvidenceRequirement]
    requireDocumentComplete: bool
    minimumNetworkQuietMillis: int
    transientBlockerTargets: list[RecoveryTargetIndicator]
    paymentSecurityRoutePrefixes: list[str]
    criticalTransactionRoutePrefixes: list[str]
    allowDepthLimited: bool
    recoveryAction: Literal['NONE', 'RELOAD', 'NAVIGATE_HOME', 'REOPEN_KNOWN_ROUTE', 'REFRESH_SESSION', 'RESTART_EXTENSION']
    recoveryExtensionId: Any
    maximumAutoRecovery: int
    enabled: bool
    approvalState: Literal['DRAFT', 'REQUESTED', 'APPROVED', 'REJECTED']
    approvalId: Any
    approvalRequestedBy: Any
    approvedBy: Any
    approvalRequestedAt: Any
    approvalDecidedAt: Any
    createdAt: str
    updatedAt: str

class RecoveryContractListResponse(TypedDict, total=False):
    items: list[RecoveryContract]
    total: int

class RecoveryContractRevisionListResponse(TypedDict, total=False):
    items: list[RecoveryContract]
    total: int
    currentVersion: int

class RecoveryContractFieldChange(TypedDict, total=False):
    field: Literal['expectedOrigins', 'readyRoutePrefixes', 'loginRoutePrefixes', 'requiredTargets', 'loginTargets', 'permissionDeniedTargets', 'accountMismatchTargets', 'requiredExtensionIds', 'requiredProviderEvidence', 'requireDocumentComplete', 'minimumNetworkQuietMillis', 'transientBlockerTargets', 'paymentSecurityRoutePrefixes', 'criticalTransactionRoutePrefixes', 'allowDepthLimited', 'recoveryAction', 'recoveryExtensionId', 'maximumAutoRecovery', 'enabled']
    changeType: Literal['MODIFIED']
    beforeValue: str
    afterValue: str

class RecoveryContractDiff(TypedDict, total=False):
    contractId: str
    applicationId: str
    fromVersion: int
    toVersion: int
    changes: list[RecoveryContractFieldChange]
    total: int

class RestoreRecoveryContractRevisionRequest(TypedDict, total=False):
    expectedCurrentVersion: int
    sourceContractVersion: int
    reason: str

class RequestRecoveryContractApprovalRequest(TypedDict, total=False):
    expectedVersion: int
    reason: str

class RecoveryContractApproval(TypedDict, total=False):
    approvalId: str
    contractId: str
    applicationId: str
    contractVersion: int
    reason: str
    state: Literal['REQUESTED', 'APPROVED', 'REJECTED']
    requestedBy: str
    approvedBy: Any
    rejectedBy: Any
    requestedAt: str
    decidedAt: Any
    evidenceHash: Any

class SessionApplicationBinding(TypedDict, total=False):
    sessionId: str
    applicationId: str
    contractId: str
    contractVersion: int
    latestContractVersion: int
    latestApprovalState: Literal['DRAFT', 'REQUESTED', 'APPROVED', 'REJECTED']
    currentContractEnabled: bool
    upgradeAvailable: bool
    boundAt: str

class RebindSessionApplicationRequest(TypedDict, total=False):
    expectedCurrentVersion: int
    targetContractVersion: int

class SessionApplicationRebind(TypedDict, total=False):
    operationId: str
    sessionId: str
    applicationId: str
    contractId: str
    previousContractVersion: int
    targetContractVersion: int
    state: Literal['COMMITTED']
    requestId: str
    createdAt: str
    completedAt: str

class BusinessRecoveryValidation(TypedDict, total=False):
    validationId: str
    sessionId: str
    applicationId: Any
    contractVersion: Any
    contextEpoch: int
    stateVersion: int
    verdict: Literal['READY', 'READY_WITH_WARNING', 'LOGIN_REQUIRED', 'PERMISSION_CHANGED', 'ACCOUNT_MISMATCH', 'APPLICATION_UNAVAILABLE', 'STATE_CHANGED', 'MANUAL_RECOVERY_REQUIRED']
    ready: bool
    evidence: list[str]
    source: Literal['API', 'MIGRATION']
    requestId: str
    evaluatedAt: str

class SubmitProviderEvidenceRequest(TypedDict, total=False):
    contextEpoch: int
    stateVersion: int
    type: Literal['ACCOUNT', 'TENANT_WORKSPACE', 'PERMISSION', 'BUSINESS_ENTITY']
    key: str
    providerId: str
    observedValueHash: str
    outcome: Literal['MATCH', 'MISMATCH', 'UNKNOWN']
    providerReference: str
    observedAt: str

class ProviderEvidence(TypedDict, total=False):
    evidenceId: str
    sessionId: str
    applicationId: str
    contractVersion: int
    contextEpoch: int
    stateVersion: int
    type: Literal['ACCOUNT', 'TENANT_WORKSPACE', 'PERMISSION', 'BUSINESS_ENTITY']
    key: str
    providerId: str
    outcome: Literal['MATCH', 'MISMATCH', 'UNKNOWN']
    valueHashMatched: bool
    providerReferenceHash: str
    adapterActorId: str
    requestId: str
    observedAt: str
    expiresAt: str
    createdAt: str

class ProviderEvidenceListResponse(TypedDict, total=False):
    items: list[ProviderEvidence]
    total: int

class ExecuteAgentBrowserActionsRequest(TypedDict, total=False):
    goal: str
    expectedStateCursor: str
    actions: list[AgentBatchActionRequest]
    stopOnError: bool

class AgentClipboard(TypedDict, total=False):
    sessionId: str
    version: int
    contentHash: Any
    valueLength: int
    value: Any
    updatedAt: Any

class WriteAgentClipboardRequest(TypedDict, total=False):
    value: str
    expectedVersion: int

class SessionIdentitySpecInput(TypedDict, total=False):
    userAgent: Any
    timezone: Any
    locale: Any
    languages: list[str]
    webRtcPolicy: Literal['DEFAULT', 'DISABLED', 'PROXY_ONLY', None]
    dnsPolicy: Literal['SYSTEM', 'PROXY', None]
    viewportWidth: Any
    viewportHeight: Any
    screenWidth: Any
    screenHeight: Any
    deviceScaleFactor: Any
    fingerprintProfile: Any
    operatingSystemProfile: Any

class SessionIdentitySpec(TypedDict, total=False):
    sessionId: str
    version: int
    specHash: str
    locked: bool
    spec: SessionIdentitySpecInput
    lockedAt: str
    updatedAt: str

class CreateSessionIdentityChangeRequest(TypedDict, total=False):
    expectedVersion: int
    proposedSpec: SessionIdentitySpecInput
    reason: str

class SessionIdentityChangeRequest(TypedDict, total=False):
    requestId: str
    sessionId: str
    expectedVersion: int
    proposedSpecHash: str
    proposedSpec: SessionIdentitySpecInput
    reason: str
    state: Literal['PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'STALE']
    createdBy: str
    decidedBy: Any
    createdAt: str
    decidedAt: Any
    appliedAt: Any

class CreateSessionRequest(TypedDict, total=False):
    tenantId: str
    profileId: str
    runtimeBuildId: str
    applicationId: str
    groupId: str
    tagIds: list[str]
    region: str
    proxyBindingProfileId: str
    resourcePolicy: ResourcePolicyRequest
    requestedTabs: int
    agentActionsPerMinute: int
    remoteDesktop: bool
    humanTakeoverEnabled: bool
    agentPolicy: AgentPolicy
    web3Workload: bool
    mediaWorkload: bool
    requestedMediaStreams: int
    mediaBitrateKbps: int
    videoRecording: bool
    extensionIds: list[str]
    metadata: dict[str, str]
    identitySpec: SessionIdentitySpecInput

class CreateSessionResponse(TypedDict, total=False):
    sessionId: str
    operationId: Any
    state: str
    resourcePolicy: ResourcePolicy
    context: SessionContext

class ResourcePolicyRequest(TypedDict, total=False):
    mode: str
    onMaximumReached: MaximumReachedPolicy
    allowMigration: bool
    allowHibernate: bool
    blockMigrationDuringHumanTakeover: bool
    executionEnvironment: ExecutionEnvironment
    minimumTemplate: Literal['standard-v1', 'interactive-v1', 'heavy-v1', 'native-standard-v1']
    maximumCpuMillis: int
    maximumMemoryMib: int
    maximumCostPerHour: float
    scaleUpWindowSeconds: int
    scaleDownWindowSeconds: int
    adjustmentCooldownSeconds: int

class ResourcePolicy(TypedDict, total=False):
    mode: str
    onMaximumReached: MaximumReachedPolicy
    allowMigration: bool
    allowHibernate: bool
    blockMigrationDuringHumanTakeover: bool
    executionEnvironment: ExecutionEnvironment
    minimumTemplate: Literal['standard-v1', 'interactive-v1', 'heavy-v1', 'native-standard-v1']
    maximumCpuMillis: int
    maximumMemoryMib: int
    maximumCostPerHour: float
    scaleUpWindowSeconds: int
    scaleDownWindowSeconds: int
    adjustmentCooldownSeconds: int
    resolvedTemplate: str

ExecutionEnvironment = Literal['SYSTEM_MANAGED', 'CONTAINER', 'ENHANCED_SANDBOX', 'MICROVM', 'NATIVE_OS']

MaximumReachedPolicy = Literal['PAUSE_AGENT', 'WAIT_SAFE_POINT_MIGRATE', 'HIBERNATE', 'TERMINATE_STRICT']

ResourcePolicyStatus = Literal['STABLE', 'OBSERVING', 'SCALING_UP', 'SCALING_DOWN', 'AT_MAXIMUM', 'WAITING_SAFE_POINT', 'MIGRATING', 'AGENT_PAUSED', 'HIBERNATING', 'CRITICAL']

class ResourceAdjustment(TypedDict, total=False):
    operationId: str
    state: Literal['REQUESTED', 'EXECUTING', 'ACKNOWLEDGED', 'COMMITTED', 'FAILED', 'RECONCILED']
    reason: str
    failureCode: Any
    oldResources: dict[str, Any]
    requestedResources: dict[str, Any]
    requestedAt: str
    executingAt: Any
    acknowledgedAt: Any
    completedAt: Any
    reconciliationOperationId: Any
    reconciledAt: Any
    updatedAt: str

class SessionResource(TypedDict, total=False):
    sessionId: str
    policy: ResourcePolicy
    allocation: Any
    usage: Any
    usageSamples: list[dict[str, Any]]
    cost: Any
    currentAdjustment: ResourceAdjustment | None
    status: ResourcePolicyStatus
    statusReason: Any
    dataFreshness: Literal['LIVE', 'STALE', 'AWAITING_TELEMETRY']
    lastEvaluatedAt: Any
    lastAdjustedAt: Any

class ResourceEventList(TypedDict, total=False):
    items: list[dict[str, Any]]
    limit: int
    offset: int

class Evidence(TypedDict, total=False):
    evidenceId: str
    evidenceKind: Literal['AGENT_ACTION_SUCCESS', 'AGENT_ACTION_FAILURE', 'AGENT_NAVIGATION_SUCCESS', 'AGENT_NAVIGATION_FAILURE', 'OBSERVER_MANUAL']
    taskId: str
    stepId: str
    commandId: str
    mandatory: bool
    result: Literal['COMMITTED', 'FAILED']
    contentSha256: Any
    contentBytes: int
    capturedAt: str
    errorCode: Any
    redactionState: Literal['LEGACY_UNVERIFIED', 'MASKED', 'NOT_REQUIRED', 'FAILED_CLOSED']
    redactedRegionCount: int

class EvidenceList(TypedDict, total=False):
    items: list[Evidence]
    limit: int
    offset: int

class Recording(TypedDict, total=False):
    recordingId: str
    nodeId: str
    segmentCount: int
    frameCount: int
    droppedFrames: int
    redactedFrameCount: int
    redactedRegionCount: int
    redactionPolicyVersion: int
    manifestSha256: str
    manifestBytes: int
    startedAt: str
    endedAt: str
    retentionUntil: str
    legalHold: bool

class RecordingList(TypedDict, total=False):
    items: list[Recording]
    limit: int
    offset: int

EvidencePurpose = Literal['INCIDENT_RESPONSE', 'CHANGE_VALIDATION', 'SUPPORT_DIAGNOSTICS', 'COMPLIANCE_AUDIT']

class CaptureEvidenceRequest(TypedDict, total=False):
    purpose: EvidencePurpose

class EvidenceCapture(TypedDict, total=False):
    captureId: str
    sessionId: str
    purpose: EvidencePurpose
    state: Literal['EXECUTING', 'COMMITTED', 'FAILED']
    evidenceId: Any
    errorCode: Any
    commandId: str
    requestId: Any
    createdAt: str
    completedAt: Any

class CreateEvidenceAccessGrantRequest(TypedDict, total=False):
    purpose: EvidencePurpose

class EvidenceAccessGrant(TypedDict, total=False):
    grantId: str
    sessionId: str
    evidenceId: str
    purpose: EvidencePurpose
    state: Literal['ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED']
    expiresAt: str
    createdAt: str
    redeemedAt: Any
    errorCode: Any
    requestId: Any

class RedeemEvidenceAccessResponse(TypedDict, total=False):
    grantId: str
    evidenceId: str
    downloadUrl: str
    expiresAt: str

class SessionSafePoint(TypedDict, total=False):
    sessionId: str
    safe: bool
    state: Literal['SAFE', 'BLOCKED', 'UNKNOWN']
    dataFreshness: Literal['LIVE', 'STALE', 'MISSING', 'NOT_REQUIRED']
    nodeId: Any
    contextEpoch: int
    evaluatedAt: str
    lastNodeObservationAt: Any
    blockers: list[SafePointBlocker]

class SafePointBlocker(TypedDict, total=False):
    code: str
    source: str
    detail: str
    observedAt: Any
    expiresAt: Any

class CreateSafetyLeaseRequest(TypedDict, total=False):
    signalType: Literal['FILE_TRANSFER', 'FORM_SUBMISSION', 'PAYMENT_OR_SECURITY', 'CRITICAL_TRANSACTION', 'BUSINESS_RECOVERY_UNKNOWN']
    reasonCode: str
    ttlSeconds: int

class RenewSafetyLeaseRequest(TypedDict, total=False):
    ttlSeconds: int

class SafetyLease(TypedDict, total=False):
    leaseId: str
    sessionId: str
    contextEpoch: int
    signalType: Literal['FILE_TRANSFER', 'FORM_SUBMISSION', 'PAYMENT_OR_SECURITY', 'CRITICAL_TRANSACTION', 'BUSINESS_RECOVERY_UNKNOWN']
    reasonCode: str
    ownerActorId: str
    state: Literal['ACTIVE', 'RELEASED', 'EXPIRED']
    acquiredAt: str
    renewedAt: str
    expiresAt: str
    releasedAt: Any

class SafetyLeaseList(TypedDict, total=False):
    items: list[SafetyLease]
    total: int

class SessionMigration(TypedDict, total=False):
    migrationId: str
    sessionId: str
    sourceNodeId: str
    targetNodeId: Any
    sourceContextEpoch: int
    targetContextEpoch: Any
    checkpointId: Any
    hibernateOperationId: Any
    restoreOperationId: Any
    targetCleanupOperationId: Any
    targetAttempt: int
    maximumTargetAttempts: int
    failedTargetNodeIds: list[str]
    lastTargetFailureReason: Any
    resyncRequestId: Any
    phase: Literal['CHECKPOINTING', 'PLACING_TARGET', 'RESTORING', 'TARGET_CLEANUP', 'STATE_RESYNC', 'BUSINESS_VALIDATION', 'BUSINESS_RECOVERY_ACTION', 'COMPLETED', 'DEGRADED', 'FAILED']
    recoveryResult: Any
    failureReason: Any
    autoRecoveryAttempts: int
    autoRecoveryMaximum: int
    latestRecoveryAction: BusinessRecoveryAction | None
    createdAt: str
    updatedAt: str
    completedAt: Any

class BusinessRecoveryAction(TypedDict, total=False):
    actionId: str
    migrationId: str
    attemptNumber: int
    action: Literal['RELOAD', 'NAVIGATE_HOME', 'REOPEN_KNOWN_ROUTE', 'REFRESH_SESSION', 'RESTART_EXTENSION']
    targetUrl: Any
    targetExtensionId: Any
    baseStateVersion: int
    resultingStateVersion: Any
    state: Literal['REQUESTED', 'EXECUTING', 'ACKNOWLEDGED', 'COMMITTED', 'FAILED']
    errorCode: Any
    createdAt: str
    completedAt: Any

class ResourcePolicyOperation(TypedDict, total=False):
    operationId: str
    state: str
    resourcePolicy: ResourcePolicy

class SessionContext(TypedDict, total=False):
    sessionId: str
    tenantId: str
    profileId: str
    nodeId: Any
    runtimeBuildId: Any
    isolationProfileId: Any
    proxyBindingId: Any
    coordinatorTerm: int
    contextEpoch: int
    browserGeneration: int
    networkRevision: int
    resourceTemplate: ResourceTemplate
    state: SessionState
    policyHash: str
    createdAt: str
    updatedAt: str

class SessionView(TypedDict, total=False):
    sessionId: str
    displayName: str
    tenantId: str
    profileId: str
    groupId: Any
    tags: list[WorkspaceTagSummary]
    humanTakeoverEnabled: bool
    agentPolicy: AgentPolicy
    extensionIds: list[str]
    region: str
    resourceTemplate: ResourceTemplate
    state: SessionState
    nodeId: Any
    runtimeBuildId: Any
    proxyBindingId: Any
    proxyBindingProfileId: Any
    proxyRoutingDecision: ProxyRoutingDecision | None
    contextEpoch: int
    browserGeneration: int
    currentOperation: OperationView | None
    createdAt: str
    updatedAt: str

EnvironmentSavedViewScope = Literal['PERSONAL', 'WORKSPACE']

EnvironmentPrimaryView = Literal['ALL', 'RUNNING', 'STOPPED', 'ABNORMAL']

EnvironmentSavedViewTagMatch = Literal['ANY', 'ALL']

class CreateEnvironmentSavedViewRequest(TypedDict, total=False):
    name: str
    scope: EnvironmentSavedViewScope
    primaryView: EnvironmentPrimaryView
    sessionState: SessionState | None
    searchQuery: str
    groupId: Any
    tagIds: list[str]
    tagMatch: Any
    showRuntimeColumn: bool
    showContextColumn: bool
    showOperationColumn: bool

class UpdateEnvironmentSavedViewRequest(TypedDict, total=False):
    expectedVersion: int
    name: str
    primaryView: EnvironmentPrimaryView
    sessionState: SessionState | None
    searchQuery: str
    groupId: Any
    tagIds: list[str]
    tagMatch: Any
    showRuntimeColumn: bool
    showContextColumn: bool
    showOperationColumn: bool

class EnvironmentSavedView(TypedDict, total=False):
    savedViewId: str
    name: str
    scope: EnvironmentSavedViewScope
    ownerActorId: str
    primaryView: EnvironmentPrimaryView
    sessionState: SessionState | None
    searchQuery: str
    groupId: Any
    tagIds: list[str]
    tagMatch: EnvironmentSavedViewTagMatch
    showRuntimeColumn: bool
    showContextColumn: bool
    showOperationColumn: bool
    createdAt: str
    updatedAt: str
    version: int

class EnvironmentSavedViewListResponse(TypedDict, total=False):
    items: list[EnvironmentSavedView]
    total: int

EnvironmentImportState = Literal['VALIDATED', 'INVALID', 'EXECUTING', 'COMMITTED']

EnvironmentImportValidationState = Literal['READY', 'INVALID']

EnvironmentImportExecutionState = Literal['PENDING', 'SUCCEEDED']

class EnvironmentImportSpec(TypedDict, total=False):
    displayName: str
    description: Any
    profileId: str
    runtimeBuildId: Any
    applicationId: Any
    groupId: Any
    tagIds: Any
    region: Any
    resourcePolicy: ResourcePolicyRequest | None
    requestedTabs: int
    agentActionsPerMinute: int
    remoteDesktop: bool
    humanTakeoverEnabled: Any
    agentPolicy: AgentPolicy | None
    web3Workload: bool
    mediaWorkload: bool
    requestedMediaStreams: int
    mediaBitrateKbps: int
    videoRecording: bool
    extensionIds: Any

class PreviewEnvironmentImportRequest(TypedDict, total=False):
    schemaVersion: int
    name: str
    environments: list[EnvironmentImportSpec]

class CommitEnvironmentImportRequest(TypedDict, total=False):
    expectedVersion: int

class EnvironmentImportItem(TypedDict, total=False):
    itemId: str
    itemIndex: int
    specification: EnvironmentImportSpec
    validationState: EnvironmentImportValidationState
    validationErrors: list[str]
    executionState: EnvironmentImportExecutionState
    sessionId: Any
    operationId: Any
    requestId: Any
    updatedAt: str

class EnvironmentImport(TypedDict, total=False):
    importId: str
    name: str
    schemaVersion: int
    manifestHash: str
    state: EnvironmentImportState
    totalCount: int
    readyCount: int
    succeededCount: int
    items: list[EnvironmentImportItem]
    createdAt: str
    updatedAt: str
    committedAt: Any
    version: int

class EnvironmentImportListItem(TypedDict, total=False):
    importId: str
    name: str
    state: EnvironmentImportState
    totalCount: int
    readyCount: int
    succeededCount: int
    createdAt: str
    updatedAt: str
    version: int

class EnvironmentImportListResponse(TypedDict, total=False):
    items: list[EnvironmentImportListItem]
    total: int

class WorkspaceGroupRequest(TypedDict, total=False):
    name: str
    description: Any
    color: str
    defaultOnMaximumReached: MaximumReachedPolicy
    defaultAllowMigration: bool
    defaultAllowHibernate: bool

class WorkspaceGroupSession(TypedDict, total=False):
    sessionId: str
    displayName: str
    state: SessionState
    region: str
    updatedAt: str

class WorkspaceGroup(TypedDict, total=False):
    groupId: str
    name: str
    description: Any
    color: str
    defaultOnMaximumReached: MaximumReachedPolicy
    defaultAllowMigration: bool
    defaultAllowHibernate: bool
    sessions: list[WorkspaceGroupSession]
    sessionCount: int
    createdBy: str
    createdAt: str
    updatedAt: str

class WorkspaceGroupListResponse(TypedDict, total=False):
    items: list[WorkspaceGroup]
    unassignedSessions: list[WorkspaceGroupSession]
    total: int

class WorkspaceTagRequest(TypedDict, total=False):
    name: str
    description: Any
    color: str

class WorkspaceTagSummary(TypedDict, total=False):
    tagId: str
    name: str
    color: str

class WorkspaceTagSession(TypedDict, total=False):
    sessionId: str
    displayName: str
    state: SessionState
    region: str
    updatedAt: str

class WorkspaceTag(TypedDict, total=False):
    tagId: str
    name: str
    description: Any
    color: str
    sessions: list[WorkspaceTagSession]
    sessionCount: int
    createdBy: str
    createdAt: str
    updatedAt: str

class WorkspaceTagListResponse(TypedDict, total=False):
    items: list[WorkspaceTag]
    sessions: list[WorkspaceTagSession]
    total: int

WorkspaceBatchAction = Literal['START', 'PAUSE_AGENT', 'MIGRATE', 'HIBERNATE']

WorkspaceBatchState = Literal['ACCEPTED', 'EXECUTING', 'CANCELLING', 'SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED']

WorkspaceBatchItemState = Literal['ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'CANCELLED']

class WorkspaceBatchSelector(TypedDict, total=False):
    groupId: Any
    tagIds: list[str]
    tagMatch: Literal['ANY', 'ALL']
    sessionIds: list[str]

class CreateWorkspaceBatchOperationRequest(TypedDict, total=False):
    action: WorkspaceBatchAction
    selector: WorkspaceBatchSelector
    reason: Any
    confirmed: bool

class CancelWorkspaceBatchOperationRequest(TypedDict, total=False):
    reason: str

class WorkspaceBatchOperationItem(TypedDict, total=False):
    batchItemId: str
    sessionId: str
    ordinal: int
    commandId: str
    state: WorkspaceBatchItemState
    childOperationId: Any
    failureCode: Any
    createdAt: str
    startedAt: Any
    completedAt: Any

class WorkspaceBatchOperation(TypedDict, total=False):
    batchOperationId: str
    action: WorkspaceBatchAction
    state: WorkspaceBatchState
    selector: WorkspaceBatchSelector
    reason: Any
    total: int
    accepted: int
    executing: int
    succeeded: int
    failed: int
    cancelled: int
    cancellationRequested: bool
    items: list[WorkspaceBatchOperationItem]
    actorId: str
    createdAt: str
    updatedAt: str

class WorkspaceBatchOperationListResponse(TypedDict, total=False):
    items: list[WorkspaceBatchOperation]
    total: int

WorkspaceMetadataBatchAction = Literal['ASSIGN_GROUP', 'REMOVE_GROUP', 'ASSIGN_TAGS', 'REMOVE_TAGS']

class WorkspaceMetadataBatchSelector(TypedDict, total=False):
    groupId: Any
    tagIds: list[str]
    tagMatch: Literal['ANY', 'ALL']
    sessionIds: list[str]

class WorkspaceMetadataBatchTarget(TypedDict, total=False):
    groupId: Any
    tagIds: list[str]

class CreateWorkspaceMetadataBatchOperationRequest(TypedDict, total=False):
    action: WorkspaceMetadataBatchAction
    selector: WorkspaceMetadataBatchSelector
    target: WorkspaceMetadataBatchTarget
    reason: str
    confirmed: bool

class WorkspaceMetadataBatchOperationItem(TypedDict, total=False):
    batchItemId: str
    sessionId: str
    ordinal: int
    state: WorkspaceBatchItemState
    failureCode: Any
    attempt: int
    createdAt: str
    startedAt: Any
    completedAt: Any

class WorkspaceMetadataBatchOperation(TypedDict, total=False):
    batchOperationId: str
    action: WorkspaceMetadataBatchAction
    state: WorkspaceBatchState
    selector: WorkspaceMetadataBatchSelector
    target: WorkspaceMetadataBatchTarget
    reason: str
    total: int
    accepted: int
    executing: int
    succeeded: int
    failed: int
    cancelled: int
    cancellationRequested: bool
    items: list[WorkspaceMetadataBatchOperationItem]
    actorId: str
    createdAt: str
    updatedAt: str

class WorkspaceMetadataBatchOperationListResponse(TypedDict, total=False):
    items: list[WorkspaceMetadataBatchOperation]
    total: int

class WorkspaceSettingsRequest(TypedDict, total=False):
    workspaceName: str
    defaultRuntimeBuildId: str
    defaultRegion: str
    defaultHumanTakeoverEnabled: bool
    remoteDesktopControlBitrateLimitKbps: int
    remoteDesktopControlFrameRateLimitFps: int
    remoteDesktopViewerBitrateLimitKbps: int
    remoteDesktopViewerFrameRateLimitFps: int

class WorkspaceSettings(TypedDict, total=False):
    workspaceName: str
    defaultRuntimeBuildId: str
    defaultRegion: str
    defaultHumanTakeoverEnabled: bool
    remoteDesktopControlBitrateLimitKbps: int
    remoteDesktopControlFrameRateLimitFps: int
    remoteDesktopViewerBitrateLimitKbps: int
    remoteDesktopViewerFrameRateLimitFps: int
    resourcePolicyMode: str
    onMaximumReached: str
    source: Literal['SYSTEM_DEFAULT', 'WORKSPACE_OVERRIDE']
    updatedBy: Any
    updatedAt: Any
    version: int

class SessionListResponse(TypedDict, total=False):
    items: list[SessionView]
    total: int
    limit: int
    offset: int

class OperationResponse(TypedDict, total=False):
    operationId: str
    state: Literal['ACTIVE', 'COMMITTED', 'ABORTED', 'TIMED_OUT']

class OperationView(TypedDict, total=False):
    operationId: str
    ownerType: Literal['AGENT', 'HUMAN', 'SYSTEM']
    actorId: Any
    mode: str
    priority: int
    coordinatorTerm: int
    contextEpoch: int
    operationEpoch: int
    workflowId: Any
    cancellable: bool
    preemptible: bool
    phase: str
    state: str
    allowedCapabilities: list[str]
    deadline: str

class RemoteDesktopConnection(TypedDict, total=False):
    connectionId: str
    webSocketPath: str
    expiresAt: str
    protocol: Literal['rfb']
    operationEpoch: int
    viewOnly: bool
    actorBitrateLimitKbps: int
    actorFrameRateLimitFps: int

class RemoteDesktopParticipantList(TypedDict, total=False):
    items: list[RemoteDesktopParticipant]
    onlineCount: int

class RemoteDesktopParticipantHistoryPage(TypedDict, total=False):
    items: list[RemoteDesktopParticipant]
    total: int
    limit: int
    nextCursor: Any
    hasMore: bool

class RemoteDesktopParticipant(TypedDict, total=False):
    connectionId: str
    sessionId: str
    contextEpoch: int
    actorId: Any
    accessMode: Literal['COLLABORATIVE', 'EXCLUSIVE_TAKEOVER', None]
    viewOnly: Any
    state: Literal['CONNECTED', 'REVOKE_REQUESTED', 'REVOKED', 'DISCONNECTED']
    reason: str
    connectedAt: Any
    disconnectedAt: Any
    revokedBy: Any
    revokeRequestedAt: Any
    observedAt: str
    updatedAt: str
    forwardedBytes: int
    quotaWaitMillis: int
    throttledBatches: int
    egressCostUsd: float
    unpricedForwardedBytes: int
    lastCostPricingVersion: Any
    lastEgressGibUsd: Any

class AuditEvent(TypedDict, total=False):
    eventId: str
    sequenceNo: int
    sessionId: Any
    eventType: str
    actorType: str
    actorId: Any
    resourceType: Any
    resourceId: Any
    action: str
    result: str
    details: dict[str, Any]
    previousEventHash: Any
    eventHash: str
    requestId: Any
    retentionUntil: str
    legalHold: bool
    createdAt: str

class AuditEventListResponse(TypedDict, total=False):
    items: list[AuditEvent]
    total: int
    chainValid: bool
    headHash: Any

class RuntimeBuild(TypedDict, total=False):
    buildId: str
    engine: str
    version: str
    platform: str
    securityTier: str
    regressionStatus: str
    releaseChannel: Literal['UNRELEASED', 'CANARY', 'STABLE', 'DISABLED']
    signatureVerified: bool
    signature: Any
    artifactDigest: Any
    signingKeyId: Any
    sbomUrl: Any
    validatedAt: Any
    releasedAt: Any
    disabledAt: Any
    disabledBy: Any
    createdAt: str

class RuntimeBuildListResponse(TypedDict, total=False):
    items: list[RuntimeBuild]
    total: int

class CreateRuntimeReleaseRequest(TypedDict, total=False):
    targetChannel: Literal['CANARY', 'STABLE']
    reason: str

class CreateRuntimeDisableRequest(TypedDict, total=False):
    reason: str

class RuntimeReleaseRequest(TypedDict, total=False):
    releaseId: str
    buildId: str
    targetChannel: Literal['CANARY', 'STABLE', 'DISABLED']
    reason: str
    state: Literal['REQUESTED', 'APPROVED', 'REJECTED']
    requestedBy: str
    approvedBy: Any
    rejectedBy: Any
    requestedAt: str
    decidedAt: Any
    evidenceHash: Any

class RuntimeReleaseRequestListResponse(TypedDict, total=False):
    items: list[RuntimeReleaseRequest]
    total: int

class CreateKeyRotationRequest(TypedDict, total=False):
    keyScope: Literal['NODE_MTLS', 'RUNTIME_SIGNING', 'PROFILE_KEK', 'REMOTE_DESKTOP', 'AGENT_CAPABILITY']
    oldKeyId: str
    newKeyId: str
    rotationTrigger: Literal['SCHEDULED', 'PERSONNEL_CHANGE', 'POLICY_CHANGE', 'SUSPECTED_COMPROMISE', 'TENANT_REQUEST']
    reason: str
    overlapMinutes: int

class CompleteKeyRotationRequest(TypedDict, total=False):
    newKeyWriteVerified: bool
    oldKeyReadVerified: bool
    plaintextRejected: bool
    affectedWorkloads: int
    verificationReference: str

class KeyRotationRequest(TypedDict, total=False):
    rotationId: str
    keyScope: str
    oldKeyId: str
    newKeyId: str
    rotationTrigger: str
    reason: str
    requestedOverlapMinutes: int
    state: Literal['REQUESTED', 'ROTATING', 'COMPLETED', 'REVOKED', 'FAILED']
    requestedBy: str
    approvedBy: Any
    completedBy: Any
    revokedBy: Any
    requestedAt: str
    approvedAt: Any
    startedAt: Any
    completedAt: Any
    revokedAt: Any
    overlapUntil: Any
    progressPercent: int
    newKeyWriteVerified: Any
    oldKeyReadVerified: Any
    plaintextRejected: Any
    affectedWorkloads: Any
    verificationReference: Any
    approvalEvidenceHash: Any
    completionEvidenceHash: Any

class KeyRotationRequestListResponse(TypedDict, total=False):
    items: list[KeyRotationRequest]
    total: int

class CreateBreakGlassRequest(TypedDict, total=False):
    ticketId: str
    reason: str
    resourceType: Literal['SESSION', 'PROFILE', 'AUDIT', 'RUNTIME', 'TENANT']
    resourceId: str
    requestedScope: Literal['READ_SENSITIVE_STATE', 'SECURE_DEBUG', 'AUDIT_EXPORT', 'INCIDENT_RESPONSE']
    durationMinutes: int

class BreakGlassRequest(TypedDict, total=False):
    requestId: str
    ticketId: str
    reason: str
    resourceType: str
    resourceId: str
    requestedScope: str
    state: Literal['REQUESTED', 'ACTIVE', 'REJECTED', 'REVOKED', 'EXPIRED']
    requestedBy: str
    approvedBy: Any
    rejectedBy: Any
    revokedBy: Any
    evidenceHash: Any
    requestedAt: str
    approvedAt: Any
    rejectedAt: Any
    revokedAt: Any
    expiresAt: str
    reviewedAt: Any

class BreakGlassRequestListResponse(TypedDict, total=False):
    items: list[BreakGlassRequest]
    total: int

class SecureDebugSession(TypedDict, total=False):
    debugSessionId: str
    breakGlassRequestId: str
    resourceType: Literal['SESSION']
    resourceId: str
    operatorId: str
    state: Literal['ACTIVE', 'ENDED', 'EXPIRED', 'REVOKED']
    startedAt: str
    expiresAt: str
    endedAt: Any
    endReason: Any
    accessCount: int
    lastAccessAt: Any
    evidenceHeadHash: Any

class SecureDebugSessionListResponse(TypedDict, total=False):
    items: list[SecureDebugSession]
    total: int

class SecureDebugSnapshot(TypedDict, total=False):
    debugSessionId: str
    sessionId: str
    sessionState: str
    runtimeBuildId: Any
    contextEpoch: int
    browserGeneration: int
    networkRevision: int
    urlOrigin: Any
    stateVersion: int
    targetRevision: int
    stateQuality: str
    stateHash: Any
    interactiveTargetCount: int
    sensitiveTargetCount: int
    capturedAt: str
    accessCount: int
    accessEvidenceHash: str
    dataClassification: Literal['SENSITIVE_MINIMIZED']
    fieldProjection: str

class StartRuntimeValidationRequest(TypedDict, total=False):
    buildId: str
    suiteVersion: str
    environmentDigest: str
    replayDatasetId: str
    persona: str
    browserEngine: str
    browserVersion: str
    operatingSystem: str
    architecture: str
    requiredWorkerCapabilities: BooleanMap
    maximumAttempts: int

class RuntimeValidationMatrixCellRequest(TypedDict, total=False):
    environmentDigest: str
    browserEngine: str
    browserVersion: str
    operatingSystem: str
    architecture: str
    requiredWorkerCapabilities: BooleanMap
    maximumAttempts: int

class StartRuntimeValidationMatrixRequest(TypedDict, total=False):
    buildId: str
    suiteVersion: str
    replayDatasetId: str
    persona: str
    cells: list[RuntimeValidationMatrixCellRequest]

class CompleteRuntimeValidationRequest(TypedDict, total=False):
    requiredTests: int
    requiredFailures: int
    optionalTests: int
    optionalFailures: int
    declaredCapabilities: BooleanMap
    observedCapabilities: BooleanMap
    optionalFailureCodes: list[str]
    personaConsistent: bool

class RuntimeValidation(TypedDict, total=False):
    validationId: str
    buildId: str
    suiteVersion: str
    environmentDigest: str
    replayDatasetId: str
    persona: str
    state: Literal['RUNNING', 'PASSED', 'DEGRADED', 'FAILED']
    requiredTests: int
    requiredFailures: int
    optionalTests: int
    optionalFailures: int
    declaredCapabilities: BooleanMap
    observedCapabilities: BooleanMap
    optionalFailureCodes: list[str]
    evidenceHash: Any
    requestedBy: str
    startedAt: str
    completedAt: Any
    job: RuntimeValidationJob | None

class RuntimeValidationJob(TypedDict, total=False):
    validationId: str
    browserEngine: str
    browserVersion: str
    operatingSystem: str
    architecture: str
    requiredWorkerCapabilities: BooleanMap
    state: Literal['QUEUED', 'CLAIMED', 'EXECUTING', 'ACKED', 'COMMITTED', 'FAILED']
    attempt: int
    maximumAttempts: int
    workerId: Any
    claimEpoch: int
    availableAt: str
    leaseExpiresAt: Any
    lastHeartbeatAt: Any
    failureCode: Any
    resultHash: Any
    updatedAt: str

class ClaimRuntimeValidationJobRequest(TypedDict, total=False):
    browserEngine: str
    browserVersions: list[str]
    operatingSystem: str
    architecture: str
    capabilities: BooleanMap

class RuntimeValidationJobClaimRequest(TypedDict, total=False):
    claimToken: str

class RuntimeValidationJobClaim(TypedDict, total=False):
    claimToken: str
    validation: RuntimeValidation
    leaseExpiresAt: str
    claimEpoch: int

class CompleteRuntimeValidationJobRequest(TypedDict, total=False):
    claimToken: str
    result: CompleteRuntimeValidationRequest

class FailRuntimeValidationJobRequest(TypedDict, total=False):
    claimToken: str
    failureCode: str
    retryable: bool

class CreateCostRateRequest(TypedDict, total=False):
    region: str
    resourceTemplate: ResourceTemplate
    baseHourlyUsd: float
    cpuCoreHourlyUsd: float
    memoryGibHourlyUsd: float
    desktopHourlyUsd: float
    remoteDesktopEgressGibUsd: float
    gpuHourlyUsd: float
    mediaHourlyUsd: float
    effectiveAt: str

class CostRate(TypedDict, total=False):
    pricingVersion: str
    region: str
    resourceTemplate: ResourceTemplate
    baseHourlyUsd: float
    cpuCoreHourlyUsd: float
    memoryGibHourlyUsd: float
    desktopHourlyUsd: float
    remoteDesktopEgressGibUsd: float
    gpuHourlyUsd: float
    mediaHourlyUsd: float
    effectiveAt: str
    createdBy: str
    createdAt: str

class SessionCostExplanation(TypedDict, total=False):
    sessionId: str
    nodeId: str
    region: str
    resourceTemplate: ResourceTemplate
    pricingVersion: str
    cpuMillis: int
    memoryRequestMib: int
    desktop: bool
    gpu: bool
    media: bool
    baseHourlyUsd: float
    cpuHourlyUsd: float
    memoryHourlyUsd: float
    desktopHourlyUsd: float
    gpuHourlyUsd: float
    mediaHourlyUsd: float
    totalHourlyUsd: float
    pricedAt: str

class UpsertMediaQuotaRequest(TypedDict, total=False):
    maxConcurrentStreams: int
    maxBitrateKbps: int

class MediaQuota(TypedDict, total=False):
    tenantId: str
    maxConcurrentStreams: int
    maxBitrateKbps: int
    activeStreams: int
    activeBitrateKbps: int
    updatedBy: str
    updatedAt: str

class UpsertSloPolicyRequest(TypedDict, total=False):
    availabilityTarget: float
    latencyP95TargetMs: int
    windowMinutes: int
    releaseFreezeEnabled: bool
    releaseFreezeBurnRateThreshold: float
    releaseRecoveryBurnRateThreshold: float
    releaseFreezeWindowMinutes: int
    releaseRecoveryStableMinutes: int

class RecordServiceLevelEventRequest(TypedDict, total=False):
    eventType: Literal['UNAVAILABLE', 'LATENCY_BREACH', 'HEALTHY']
    durationSeconds: int
    latencyP95Ms: Any
    source: str
    occurredAt: str
    exclusionCode: Any

class UpsertSlaExclusionRequest(TypedDict, total=False):
    description: str
    enabled: bool

class SlaExclusion(TypedDict, total=False):
    tenantId: str
    exclusionCode: str
    description: str
    enabled: bool
    updatedBy: str
    updatedAt: str

class ErrorBudget(TypedDict, total=False):
    tenantId: str
    availabilityTarget: float
    latencyP95TargetMs: int
    windowMinutes: int
    allowedUnavailableSeconds: int
    consumedUnavailableSeconds: int
    remainingUnavailableSeconds: int
    burnRatio: float
    state: Literal['HEALTHY', 'EXHAUSTED']
    windowStartedAt: str
    calculatedAt: str

class ReleaseFreeze(TypedDict, total=False):
    tenantId: str
    enabled: bool
    phase: Literal['OPEN', 'FROZEN', 'RECOVERING']
    frozen: bool
    currentBurnRate: float
    freezeBurnRateThreshold: float
    recoveryBurnRateThreshold: float
    evaluationWindowMinutes: int
    recoveryStableMinutes: int
    reasonCode: str
    stableSince: Any
    frozenAt: Any
    clearedAt: Any
    evaluatedAt: str
    version: int

class UpsertRetentionPolicyRequest(TypedDict, total=False):
    dataClass: Literal['AUDIT', 'AGENT_EXECUTION', 'PROFILE_CHECKPOINT', 'REMOTE_DESKTOP_RECORDING', 'SECURE_DEBUG']
    retentionDays: int
    legalHold: bool
    residencyRegion: str

class RetentionPolicy(TypedDict, total=False):
    tenantId: str
    dataClass: str
    retentionDays: int
    legalHold: bool
    residencyRegion: str
    updatedBy: str
    updatedAt: str

class CreateDeletionReceiptRequest(TypedDict, total=False):
    dataClass: Literal['AUDIT', 'AGENT_EXECUTION', 'PROFILE_CHECKPOINT', 'REMOTE_DESKTOP_RECORDING', 'SECURE_DEBUG']
    objectId: str
    contentDigest: str

class DeletionReceipt(TypedDict, total=False):
    receiptId: str
    tenantId: str
    dataClass: str
    objectId: str
    contentDigest: str
    policyUpdatedAt: str
    receiptHash: str
    deletedBy: str
    deletedAt: str

class UpsertLicenseInventoryRequest(TypedDict, total=False):
    componentType: Literal['RUNTIME', 'EXTENSION', 'SERVICE', 'SDK']
    componentName: str
    componentVersion: str
    licenseId: str
    sourceUrl: str
    approved: bool

class LicenseInventory(TypedDict, total=False):
    componentId: str
    componentType: Literal['RUNTIME', 'EXTENSION', 'SERVICE', 'SDK']
    componentName: str
    componentVersion: str
    licenseId: str
    sourceUrl: str
    approved: bool
    evidenceHash: str
    updatedBy: str
    updatedAt: str

class AuditExportManifest(TypedDict, total=False):
    exportId: str
    tenantId: str
    fromSequence: int
    toSequence: int
    eventCount: int
    firstEventHash: str
    lastEventHash: str
    manifestHash: str
    signatureAlgorithm: Literal['HMAC-SHA256']
    signingKeyId: str
    signature: str
    generatedBy: str
    generatedAt: str

class UpsertRegionRequest(TypedDict, total=False):
    role: Literal['PRIMARY', 'SECONDARY', 'DR']
    admissionState: Literal['OPEN', 'CLOSED', 'FAILOVER_READY']
    replicationLagSeconds: int

class EnterpriseRegion(TypedDict, total=False):
    regionId: str
    role: Literal['PRIMARY', 'SECONDARY', 'DR']
    admissionState: Literal['OPEN', 'CLOSED', 'FAILOVER_READY']
    replicationLagSeconds: int
    lastVerifiedAt: str
    updatedBy: str

class StartRecoveryGameDayRequest(TypedDict, total=False):
    scenario: str
    sourceRegion: str
    targetRegion: str
    rtoTargetSeconds: int
    rpoTargetSeconds: int
    executionMode: Literal['MANUAL', 'AUTO']
    environment: Literal['TEST', 'STAGING', 'PRODUCTION']
    blastRadius: RecoveryGameDayBlastRadius
    maximumDurationSeconds: int
    approvalRequestId: str
    requiredWorkerCapabilities: dict[str, bool]
    maximumAttempts: int

class RecoveryGameDayBlastRadius(TypedDict, total=False):
    scope: Literal['TEST_FIXTURE', 'TENANT', 'NAMESPACE', 'REGION']
    maximumTargets: int
    targetIds: list[str]

class CompleteRecoveryGameDayRequest(TypedDict, total=False):
    observedRtoSeconds: int
    observedRpoSeconds: int
    dataLossRecords: int
    detectionTimeSeconds: int
    failoverTimeSeconds: int
    staleOperationCount: int
    userImpactCount: int
    manualSteps: int
    runbookAccuracyPercent: int
    runnerEvidenceHash: str
    recoveryConfirmed: bool

class ClaimRecoveryGameDayJobRequest(TypedDict, total=False):
    environments: list[Literal['TEST', 'STAGING', 'PRODUCTION']]
    scenarioCodes: list[str]
    capabilities: dict[str, bool]

class RecoveryGameDayJobClaimRequest(TypedDict, total=False):
    claimToken: str

class UpdateRecoveryGameDayStageRequest(TypedDict, total=False):
    claimToken: str
    stage: Literal['INJECTING', 'FAULT_INJECTED', 'OBSERVING', 'RECOVERING', 'VALIDATING']

class CompleteRecoveryGameDayJobRequest(TypedDict, total=False):
    claimToken: str
    result: CompleteRecoveryGameDayRequest

class FailRecoveryGameDayJobRequest(TypedDict, total=False):
    claimToken: str
    failureCode: str
    retryable: bool
    recoveryConfirmed: bool

class RecoveryGameDayJob(TypedDict, total=False):
    gameDayId: str
    scenarioCode: str
    environment: Literal['TEST', 'STAGING', 'PRODUCTION']
    requiredWorkerCapabilities: dict[str, bool]
    state: Literal['QUEUED', 'CLAIMED', 'EXECUTING', 'RECOVERY_REQUIRED', 'RECOVERING', 'ACKED', 'COMMITTED', 'FAILED', 'ABORTED']
    currentStage: Literal['QUEUED', 'PREPARING', 'INJECTING', 'FAULT_INJECTED', 'OBSERVING', 'RECOVERY_REQUIRED', 'RECOVERING', 'VALIDATING', 'COMMITTED', 'FAILED', 'ABORTED']
    attempt: int
    maximumAttempts: int
    recoveryAttempt: int
    maximumRecoveryAttempts: int
    workerId: Any
    claimEpoch: int
    availableAt: str
    leaseExpiresAt: Any
    lastHeartbeatAt: Any
    abortDeadline: str
    abortRequested: bool
    faultInjected: bool
    recoveryConfirmed: Any
    failureCode: Any
    resultHash: Any
    updatedAt: str

class RecoveryGameDayJobClaim(TypedDict, total=False):
    claimToken: str
    gameDay: RecoveryGameDay
    leaseExpiresAt: str
    claimEpoch: int
    recoveryOnly: bool

class RecoveryGameDay(TypedDict, total=False):
    gameDayId: str
    scenario: str
    sourceRegion: str
    targetRegion: str
    state: Literal['QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'ABORTED']
    rtoTargetSeconds: int
    rpoTargetSeconds: int
    observedRtoSeconds: Any
    observedRpoSeconds: Any
    dataLossRecords: Any
    evidenceHash: Any
    startedBy: str
    startedAt: str
    completedAt: Any
    executionMode: Literal['MANUAL', 'AUTO']
    environment: Literal['TEST', 'STAGING', 'PRODUCTION']
    blastRadius: RecoveryGameDayBlastRadius | None
    maximumDurationSeconds: int
    approvalRequestId: Any
    currentStage: str
    abortRequested: bool
    recoveryConfirmed: Any
    failureCode: Any
    job: RecoveryGameDayJob | None

class RecoveryGameDayEvent(TypedDict, total=False):
    eventId: str
    gameDayId: str
    eventType: str
    fromState: Any
    toState: str
    stage: str
    workerId: Any
    claimEpoch: int
    attempt: int
    reasonCode: Any
    occurredAt: str

class RecoveryGameDayEventPage(TypedDict, total=False):
    items: list[RecoveryGameDayEvent]
    nextCursor: Any
    hasMore: bool

class RecoveryGameDayTrend(TypedDict, total=False):
    scenario: str
    environment: Literal['TEST', 'STAGING', 'PRODUCTION']
    totalRuns: int
    passedRuns: int
    failedRuns: int
    abortedRuns: int
    recoveryUnknownRuns: int
    passRatePercent: float
    p95RtoSeconds: Any
    p95RpoSeconds: Any
    openTicketCount: int
    latestRunAt: str

class RecoveryGameDayReportExport(TypedDict, total=False):
    exportId: str
    gameDayId: str
    reportFormat: Literal['JSON']
    eventCount: int
    report: dict[str, Any]
    reportHash: str
    signatureAlgorithm: Literal['HMAC-SHA256']
    signingKeyId: str
    signature: str
    generatedBy: str
    generatedAt: str

class UpdateRecoveryGameDayRemediationRequest(TypedDict, total=False):
    state: Literal['ACKNOWLEDGED', 'RESOLVED']
    ownerId: str
    resolution: str

class RecoveryGameDayRemediation(TypedDict, total=False):
    ticketId: str
    gameDayId: str
    scenario: str
    environment: Literal['TEST', 'STAGING', 'PRODUCTION']
    severity: Literal['P1', 'P2', 'P3']
    state: Literal['OPEN', 'ACKNOWLEDGED', 'RESOLVED']
    reasonCode: str
    summary: str
    ownerId: Any
    resolution: Any
    createdBy: str
    createdAt: str
    updatedBy: str
    updatedAt: str
    resolvedAt: Any

class ComplianceSnapshot(TypedDict, total=False):
    snapshotId: str
    tenantId: str
    framework: str
    controlCount: int
    passingControls: int
    evidenceHash: str
    evidence: BooleanMap
    generatedBy: str
    generatedAt: str

class EnterpriseOverview(TypedDict, total=False):
    validations: list[RuntimeValidation]
    costRates: list[CostRate]
    mediaQuota: MediaQuota | None
    errorBudget: ErrorBudget | None
    releaseFreeze: ReleaseFreeze | None
    slaExclusions: list[SlaExclusion]
    retentionPolicies: list[RetentionPolicy]
    licenseInventory: list[LicenseInventory]
    regions: list[EnterpriseRegion]
    recoveryGameDays: list[RecoveryGameDay]
    recoveryGameDayTrends: list[RecoveryGameDayTrend]
    recoveryGameDayRemediations: list[RecoveryGameDayRemediation]
    latestCompliance: ComplianceSnapshot | None
    generatedAt: str

class EnterpriseOverviewStreamControl(TypedDict, total=False):
    cursor: int
    resetRequired: bool
    connectedAt: str

class EnterpriseOverviewStreamChange(TypedDict, total=False):
    sequence: int
    changeType: Literal['RUNTIME_VALIDATION', 'COST_RATE', 'MEDIA_QUOTA', 'ERROR_BUDGET', 'RELEASE_FREEZE', 'SLA_EXCLUSION', 'RETENTION', 'LICENSE', 'REGION', 'RECOVERY_GAMEDAY', 'COMPLIANCE']
    occurredAt: str
    replayed: bool

class BooleanMap(TypedDict, total=False):
    pass

class Error(TypedDict, total=False):
    code: str
    message: str
    details: dict[str, Any]
    requestId: str
    timestamp: str

__all__ = ['WorkspaceOverview', 'WorkspaceSessionSummary', 'WorkspaceOperationSummary', 'WorkspaceBrowserNodeSummary', 'WorkspaceProxySummary', 'WorkspaceAgentSummary', 'WorkspaceCostSummary', 'WorkspaceSecuritySummary', 'SearchResourceType', 'GlobalSearchResult', 'GlobalSearchResponse', 'NotificationCategory', 'NotificationSeverity', 'WorkspaceNotification', 'WorkspaceNotificationListResponse', 'UpdateNotificationReadCursorRequest', 'WorkspaceNotificationReadState', 'ThemeMode', 'UpdateUserPreferencesRequest', 'UserPreferences', 'TenantRoute', 'RequestTenantRouteMigration', 'TenantRouteMigration', 'CreateAgentTaskRequest', 'AgentActionRequest', 'AgentBatchActionRequest', 'AgentInstructionSource', 'AgentTaskListResponse', 'AgentTaskSummaryListResponse', 'AgentTaskSummaryMetrics', 'AgentTaskSummary', 'ChallengeAutomationPolicy', 'UpdateChallengeAutomationPolicyRequest', 'CreateAgentInputSecretRequest', 'AgentInputSecret', 'ChallengeAutomationRun', 'ClaimChallengeVisualJobRequest', 'ChallengeVisualJobClaimRequest', 'ChallengeVisualAction', 'CompleteChallengeVisualJobRequest', 'FailChallengeVisualJobRequest', 'ChallengeVisualJob', 'ChallengeVisualJobClaim', 'ChallengeRegion', 'ChallengeEvent', 'ChallengeEventListResponse', 'ChallengePreview', 'AuthorizeHumanAssistRequest', 'HumanAssistIntent', 'SubmitChallengeInputResponseRequest', 'ChallengeInputResponse', 'AgentTask', 'ClaimAgentExecutionJobRequest', 'AgentExecutionJobClaimRequest', 'FailAgentExecutionJobRequest', 'AgentExecutionJob', 'AgentExecutionJobClaim', 'ClaimAgentReviewJobRequest', 'AgentReviewJobClaimRequest', 'CompleteAgentReviewJobRequest', 'FailAgentReviewJobRequest', 'AgentReviewStep', 'AgentReviewPayload', 'ReviewerModelDeployment', 'AgentReviewJob', 'AgentReviewJobClaim', 'AgentReview', 'AgentStepExecution', 'AgentExecutionWait', 'AgentConfirmation', 'AgentHumanHandoff', 'AgentPlan', 'AgentPlanStep', 'AgentStepInput', 'AgentBatchActionInput', 'AgentRiskClass', 'AgentPolicy', 'AgentToolExecutionResult', 'PromptSecurityEvent', 'CreateProfileRequest', 'CreateProfileExportGrantRequest', 'ProfileExportPurpose', 'ProfileExportGrant', 'RedeemProfileExportResponse', 'Profile', 'ProfileListResponse', 'ProfileWarmTierStatus', 'ProfileImport', 'ProfileImportListResponse', 'ProxyProvider', 'ProxyAllocation', 'ProxyOverview', 'ProxyBindingHealth', 'ProxyBindingRequest', 'ProxyBinding', 'ProxyBindingList', 'ProxyRoutingCandidateScore', 'ProxyRoutingDecision', 'ProxyRebindRequest', 'ProxyRebindOperation', 'ProxyRebind', 'SessionState', 'ResourceTemplate', 'RegisterBrowserNodeRequest', 'RecordNodePressureRequest', 'BrowserNode', 'BrowserNodeListResponse', 'UpsertExtensionProfileRequest', 'ExtensionProfile', 'RecordExtensionSampleRequest', 'ExtensionProfileListResponse', 'BrowserPlacement', 'BrowserState', 'AgentBrowserSnapshot', 'AgentBrowserTab', 'AgentBrowserInspectRequest', 'AgentBrowserFindRequest', 'AgentBrowserTargetList', 'StateResyncRequest', 'StateResyncResponse', 'InteractiveTarget', 'TargetBounds', 'RecoveryTargetIndicator', 'ProviderEvidenceRequirement', 'UpsertRecoveryContractRequest', 'RecoveryContract', 'RecoveryContractListResponse', 'RecoveryContractRevisionListResponse', 'RecoveryContractFieldChange', 'RecoveryContractDiff', 'RestoreRecoveryContractRevisionRequest', 'RequestRecoveryContractApprovalRequest', 'RecoveryContractApproval', 'SessionApplicationBinding', 'RebindSessionApplicationRequest', 'SessionApplicationRebind', 'BusinessRecoveryValidation', 'SubmitProviderEvidenceRequest', 'ProviderEvidence', 'ProviderEvidenceListResponse', 'ExecuteAgentBrowserActionsRequest', 'AgentClipboard', 'WriteAgentClipboardRequest', 'SessionIdentitySpecInput', 'SessionIdentitySpec', 'CreateSessionIdentityChangeRequest', 'SessionIdentityChangeRequest', 'CreateSessionRequest', 'CreateSessionResponse', 'ResourcePolicyRequest', 'ResourcePolicy', 'ExecutionEnvironment', 'MaximumReachedPolicy', 'ResourcePolicyStatus', 'ResourceAdjustment', 'SessionResource', 'ResourceEventList', 'Evidence', 'EvidenceList', 'Recording', 'RecordingList', 'EvidencePurpose', 'CaptureEvidenceRequest', 'EvidenceCapture', 'CreateEvidenceAccessGrantRequest', 'EvidenceAccessGrant', 'RedeemEvidenceAccessResponse', 'SessionSafePoint', 'SafePointBlocker', 'CreateSafetyLeaseRequest', 'RenewSafetyLeaseRequest', 'SafetyLease', 'SafetyLeaseList', 'SessionMigration', 'BusinessRecoveryAction', 'ResourcePolicyOperation', 'SessionContext', 'SessionView', 'EnvironmentSavedViewScope', 'EnvironmentPrimaryView', 'EnvironmentSavedViewTagMatch', 'CreateEnvironmentSavedViewRequest', 'UpdateEnvironmentSavedViewRequest', 'EnvironmentSavedView', 'EnvironmentSavedViewListResponse', 'EnvironmentImportState', 'EnvironmentImportValidationState', 'EnvironmentImportExecutionState', 'EnvironmentImportSpec', 'PreviewEnvironmentImportRequest', 'CommitEnvironmentImportRequest', 'EnvironmentImportItem', 'EnvironmentImport', 'EnvironmentImportListItem', 'EnvironmentImportListResponse', 'WorkspaceGroupRequest', 'WorkspaceGroupSession', 'WorkspaceGroup', 'WorkspaceGroupListResponse', 'WorkspaceTagRequest', 'WorkspaceTagSummary', 'WorkspaceTagSession', 'WorkspaceTag', 'WorkspaceTagListResponse', 'WorkspaceBatchAction', 'WorkspaceBatchState', 'WorkspaceBatchItemState', 'WorkspaceBatchSelector', 'CreateWorkspaceBatchOperationRequest', 'CancelWorkspaceBatchOperationRequest', 'WorkspaceBatchOperationItem', 'WorkspaceBatchOperation', 'WorkspaceBatchOperationListResponse', 'WorkspaceMetadataBatchAction', 'WorkspaceMetadataBatchSelector', 'WorkspaceMetadataBatchTarget', 'CreateWorkspaceMetadataBatchOperationRequest', 'WorkspaceMetadataBatchOperationItem', 'WorkspaceMetadataBatchOperation', 'WorkspaceMetadataBatchOperationListResponse', 'WorkspaceSettingsRequest', 'WorkspaceSettings', 'SessionListResponse', 'OperationResponse', 'OperationView', 'RemoteDesktopConnection', 'RemoteDesktopParticipantList', 'RemoteDesktopParticipantHistoryPage', 'RemoteDesktopParticipant', 'AuditEvent', 'AuditEventListResponse', 'RuntimeBuild', 'RuntimeBuildListResponse', 'CreateRuntimeReleaseRequest', 'CreateRuntimeDisableRequest', 'RuntimeReleaseRequest', 'RuntimeReleaseRequestListResponse', 'CreateKeyRotationRequest', 'CompleteKeyRotationRequest', 'KeyRotationRequest', 'KeyRotationRequestListResponse', 'CreateBreakGlassRequest', 'BreakGlassRequest', 'BreakGlassRequestListResponse', 'SecureDebugSession', 'SecureDebugSessionListResponse', 'SecureDebugSnapshot', 'StartRuntimeValidationRequest', 'RuntimeValidationMatrixCellRequest', 'StartRuntimeValidationMatrixRequest', 'CompleteRuntimeValidationRequest', 'RuntimeValidation', 'RuntimeValidationJob', 'ClaimRuntimeValidationJobRequest', 'RuntimeValidationJobClaimRequest', 'RuntimeValidationJobClaim', 'CompleteRuntimeValidationJobRequest', 'FailRuntimeValidationJobRequest', 'CreateCostRateRequest', 'CostRate', 'SessionCostExplanation', 'UpsertMediaQuotaRequest', 'MediaQuota', 'UpsertSloPolicyRequest', 'RecordServiceLevelEventRequest', 'UpsertSlaExclusionRequest', 'SlaExclusion', 'ErrorBudget', 'ReleaseFreeze', 'UpsertRetentionPolicyRequest', 'RetentionPolicy', 'CreateDeletionReceiptRequest', 'DeletionReceipt', 'UpsertLicenseInventoryRequest', 'LicenseInventory', 'AuditExportManifest', 'UpsertRegionRequest', 'EnterpriseRegion', 'StartRecoveryGameDayRequest', 'RecoveryGameDayBlastRadius', 'CompleteRecoveryGameDayRequest', 'ClaimRecoveryGameDayJobRequest', 'RecoveryGameDayJobClaimRequest', 'UpdateRecoveryGameDayStageRequest', 'CompleteRecoveryGameDayJobRequest', 'FailRecoveryGameDayJobRequest', 'RecoveryGameDayJob', 'RecoveryGameDayJobClaim', 'RecoveryGameDay', 'RecoveryGameDayEvent', 'RecoveryGameDayEventPage', 'RecoveryGameDayTrend', 'RecoveryGameDayReportExport', 'UpdateRecoveryGameDayRemediationRequest', 'RecoveryGameDayRemediation', 'ComplianceSnapshot', 'EnterpriseOverview', 'EnterpriseOverviewStreamControl', 'EnterpriseOverviewStreamChange', 'BooleanMap', 'Error']
