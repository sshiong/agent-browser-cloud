// Code generated from session-api.yaml; DO NOT EDIT.

package generated

type WorkspaceOverview struct {
	Sessions     WorkspaceSessionSummary     `json:"sessions,omitempty"`
	Operations   WorkspaceOperationSummary   `json:"operations,omitempty"`
	BrowserNodes WorkspaceBrowserNodeSummary `json:"browserNodes,omitempty"`
	Proxies      WorkspaceProxySummary       `json:"proxies,omitempty"`
	Agents       WorkspaceAgentSummary       `json:"agents,omitempty"`
	Cost         WorkspaceCostSummary        `json:"cost,omitempty"`
	Security     WorkspaceSecuritySummary    `json:"security,omitempty"`
	Cursor       int64                       `json:"cursor,omitempty"`
	GeneratedAt  string                      `json:"generatedAt,omitempty"`
}

type WorkspaceSessionSummary struct {
	Total      int64 `json:"total,omitempty"`
	Running    int64 `json:"running,omitempty"`
	Pending    int64 `json:"pending,omitempty"`
	Unhealthy  int64 `json:"unhealthy,omitempty"`
	Hibernated int64 `json:"hibernated,omitempty"`
	Terminated int64 `json:"terminated,omitempty"`
}

type WorkspaceOperationSummary struct {
	Active int64 `json:"active,omitempty"`
}

type WorkspaceBrowserNodeSummary struct {
	Visible            bool  `json:"visible,omitempty"`
	Total              int64 `json:"total,omitempty"`
	Ready              int64 `json:"ready,omitempty"`
	Constrained        int64 `json:"constrained,omitempty"`
	ActiveSessions     int64 `json:"activeSessions,omitempty"`
	MaximumSessions    int64 `json:"maximumSessions,omitempty"`
	ReservedCpuMillis  int64 `json:"reservedCpuMillis,omitempty"`
	CertifiedCpuMillis int64 `json:"certifiedCpuMillis,omitempty"`
	ReservedMemoryMib  int64 `json:"reservedMemoryMib,omitempty"`
	CertifiedMemoryMib int64 `json:"certifiedMemoryMib,omitempty"`
}

type WorkspaceProxySummary struct {
	ActiveAllocations int64 `json:"activeAllocations,omitempty"`
	BoundSessions     int64 `json:"boundSessions,omitempty"`
}

type WorkspaceAgentSummary struct {
	Active                 int64 `json:"active,omitempty"`
	AwaitingHuman          int64 `json:"awaitingHuman,omitempty"`
	PausedByResourcePolicy int64 `json:"pausedByResourcePolicy,omitempty"`
	FailedLast24Hours      int64 `json:"failedLast24Hours,omitempty"`
}

type WorkspaceCostSummary struct {
	CurrentHourlyUsd                  float64 `json:"currentHourlyUsd,omitempty"`
	ActiveSessionsWithoutCurrentPrice int64   `json:"activeSessionsWithoutCurrentPrice,omitempty"`
}

type WorkspaceSecuritySummary struct {
	WarningLast24Hours  int64 `json:"warningLast24Hours,omitempty"`
	CriticalLast24Hours int64 `json:"criticalLast24Hours,omitempty"`
}

type SearchResourceType string

const (
	SearchResourceTypeSESSION SearchResourceType = "SESSION"
	SearchResourceTypePROFILE SearchResourceType = "PROFILE"
	SearchResourceTypeGROUP   SearchResourceType = "GROUP"
	SearchResourceTypeTAG     SearchResourceType = "TAG"
	SearchResourceTypeRUNTIME SearchResourceType = "RUNTIME"
	SearchResourceTypeNODE    SearchResourceType = "NODE"
)

type GlobalSearchResult struct {
	ResourceType SearchResourceType `json:"resourceType,omitempty"`
	ResourceId   string             `json:"resourceId,omitempty"`
	Title        string             `json:"title,omitempty"`
	Description  any                `json:"description,omitempty"`
	Status       any                `json:"status,omitempty"`
	Region       any                `json:"region,omitempty"`
	UpdatedAt    any                `json:"updatedAt,omitempty"`
}

type GlobalSearchResponse struct {
	Query     string               `json:"query,omitempty"`
	Items     []GlobalSearchResult `json:"items,omitempty"`
	Limit     int                  `json:"limit,omitempty"`
	Truncated bool                 `json:"truncated,omitempty"`
}

type NotificationCategory string

const (
	NotificationCategorySECURITY NotificationCategory = "SECURITY"
	NotificationCategoryRESOURCE NotificationCategory = "RESOURCE"
	NotificationCategoryAGENT    NotificationCategory = "AGENT"
	NotificationCategoryRELEASE  NotificationCategory = "RELEASE"
	NotificationCategorySYSTEM   NotificationCategory = "SYSTEM"
)

type NotificationSeverity string

const (
	NotificationSeverityINFO     NotificationSeverity = "INFO"
	NotificationSeverityWARNING  NotificationSeverity = "WARNING"
	NotificationSeverityCRITICAL NotificationSeverity = "CRITICAL"
)

type WorkspaceNotification struct {
	NotificationId string               `json:"notificationId,omitempty"`
	Sequence       int64                `json:"sequence,omitempty"`
	Category       NotificationCategory `json:"category,omitempty"`
	Severity       NotificationSeverity `json:"severity,omitempty"`
	Title          string               `json:"title,omitempty"`
	Body           string               `json:"body,omitempty"`
	EventType      string               `json:"eventType,omitempty"`
	SessionId      any                  `json:"sessionId,omitempty"`
	ResourceType   any                  `json:"resourceType,omitempty"`
	ResourceId     any                  `json:"resourceId,omitempty"`
	RequestId      any                  `json:"requestId,omitempty"`
	Route          string               `json:"route,omitempty"`
	Read           bool                 `json:"read,omitempty"`
	OccurredAt     string               `json:"occurredAt,omitempty"`
}

type WorkspaceNotificationListResponse struct {
	Items              []WorkspaceNotification `json:"items,omitempty"`
	UnreadCount        int64                   `json:"unreadCount,omitempty"`
	LastReadSequence   int64                   `json:"lastReadSequence,omitempty"`
	HeadSequence       int64                   `json:"headSequence,omitempty"`
	NextBeforeSequence any                     `json:"nextBeforeSequence,omitempty"`
}

type UpdateNotificationReadCursorRequest struct {
	ReadThroughSequence int64 `json:"readThroughSequence,omitempty"`
}

type WorkspaceNotificationReadState struct {
	LastReadSequence int64  `json:"lastReadSequence,omitempty"`
	UnreadCount      int64  `json:"unreadCount,omitempty"`
	UpdatedAt        string `json:"updatedAt,omitempty"`
}

type ThemeMode string

const (
	ThemeModeSYSTEM ThemeMode = "SYSTEM"
	ThemeModeDARK   ThemeMode = "DARK"
	ThemeModeLIGHT  ThemeMode = "LIGHT"
)

type UpdateUserPreferencesRequest struct {
	ThemeMode ThemeMode `json:"themeMode,omitempty"`
}

type UserPreferences struct {
	ThemeMode ThemeMode `json:"themeMode,omitempty"`
	Source    string    `json:"source,omitempty"`
	UpdatedAt any       `json:"updatedAt,omitempty"`
	Version   int64     `json:"version,omitempty"`
}

type TenantRoute struct {
	TenantId                 string `json:"tenantId,omitempty"`
	State                    string `json:"state,omitempty"`
	ActiveVirtualPartitions  int    `json:"activeVirtualPartitions,omitempty"`
	ActiveRouteEpoch         int64  `json:"activeRouteEpoch,omitempty"`
	PendingVirtualPartitions any    `json:"pendingVirtualPartitions,omitempty"`
	PendingRouteEpoch        any    `json:"pendingRouteEpoch,omitempty"`
	ActiveMigrationId        any    `json:"activeMigrationId,omitempty"`
	Version                  int64  `json:"version,omitempty"`
	UpdatedAt                string `json:"updatedAt,omitempty"`
}

type RequestTenantRouteMigration struct {
	ExpectedRouteEpoch      int64 `json:"expectedRouteEpoch,omitempty"`
	TargetVirtualPartitions int   `json:"targetVirtualPartitions,omitempty"`
}

type TenantRouteMigration struct {
	MigrationId             string `json:"migrationId,omitempty"`
	TenantId                string `json:"tenantId,omitempty"`
	SourceRouteEpoch        int64  `json:"sourceRouteEpoch,omitempty"`
	TargetRouteEpoch        int64  `json:"targetRouteEpoch,omitempty"`
	SourceVirtualPartitions int    `json:"sourceVirtualPartitions,omitempty"`
	TargetVirtualPartitions int    `json:"targetVirtualPartitions,omitempty"`
	State                   string `json:"state,omitempty"`
	TotalSessions           int    `json:"totalSessions,omitempty"`
	MigratedSessions        int    `json:"migratedSessions,omitempty"`
	BlockedSessions         int    `json:"blockedSessions,omitempty"`
	RequestedBy             string `json:"requestedBy,omitempty"`
	RequestId               string `json:"requestId,omitempty"`
	FailureCode             any    `json:"failureCode,omitempty"`
	CreatedAt               string `json:"createdAt,omitempty"`
	UpdatedAt               string `json:"updatedAt,omitempty"`
	CompletedAt             any    `json:"completedAt,omitempty"`
}

type CreateAgentTaskRequest struct {
	Goal           string                   `json:"goal,omitempty"`
	StartUrl       string                   `json:"startUrl,omitempty"`
	AllowedDomains []string                 `json:"allowedDomains,omitempty"`
	MaxActions     int                      `json:"maxActions,omitempty"`
	ReplanBudget   int                      `json:"replanBudget,omitempty"`
	ContextSources []AgentInstructionSource `json:"contextSources,omitempty"`
	Actions        []AgentActionRequest     `json:"actions,omitempty"`
}

type AgentActionRequest struct {
	ToolId         string `json:"toolId,omitempty"`
	TargetRef      string `json:"targetRef,omitempty"`
	TargetRevision int64  `json:"targetRevision,omitempty"`
	Value          string `json:"value,omitempty"`
	DataClass      string `json:"dataClass,omitempty"`
	ScrollDeltaY   int    `json:"scrollDeltaY,omitempty"`
	WaitCondition  string `json:"waitCondition,omitempty"`
	TimeoutMs      int    `json:"timeoutMs,omitempty"`
}

type AgentInstructionSource struct {
	SourceId       string `json:"sourceId,omitempty"`
	SourceType     string `json:"sourceType,omitempty"`
	Classification string `json:"classification,omitempty"`
	Content        string `json:"content,omitempty"`
}

type AgentTaskListResponse struct {
	Items  []AgentTask `json:"items,omitempty"`
	Total  int64       `json:"total,omitempty"`
	Limit  int         `json:"limit,omitempty"`
	Offset int         `json:"offset,omitempty"`
}

type AgentTaskSummaryListResponse struct {
	Items      []AgentTaskSummary      `json:"items,omitempty"`
	Metrics    AgentTaskSummaryMetrics `json:"metrics,omitempty"`
	Total      int64                   `json:"total,omitempty"`
	Limit      int                     `json:"limit,omitempty"`
	NextCursor any                     `json:"nextCursor,omitempty"`
	HasMore    bool                    `json:"hasMore,omitempty"`
}

type AgentTaskSummaryMetrics struct {
	Planned   int64 `json:"planned,omitempty"`
	Completed int64 `json:"completed,omitempty"`
	Blocked   int64 `json:"blocked,omitempty"`
}

type AgentTaskSummary struct {
	TaskId             string `json:"taskId,omitempty"`
	SessionId          string `json:"sessionId,omitempty"`
	Goal               string `json:"goal,omitempty"`
	State              string `json:"state,omitempty"`
	RiskClass          string `json:"riskClass,omitempty"`
	IntentDecision     string `json:"intentDecision,omitempty"`
	BlockedReason      any    `json:"blockedReason,omitempty"`
	AgentPolicy        string `json:"agentPolicy,omitempty"`
	CurrentStep        int    `json:"currentStep,omitempty"`
	TotalSteps         int    `json:"totalSteps,omitempty"`
	SecurityEventCount int    `json:"securityEventCount,omitempty"`
	CreatedAt          string `json:"createdAt,omitempty"`
	UpdatedAt          string `json:"updatedAt,omitempty"`
}

type AgentTask struct {
	TaskId           string                     `json:"taskId,omitempty"`
	SessionId        string                     `json:"sessionId,omitempty"`
	Goal             string                     `json:"goal,omitempty"`
	State            string                     `json:"state,omitempty"`
	RiskClass        AgentRiskClass             `json:"riskClass,omitempty"`
	IntentDecision   string                     `json:"intentDecision,omitempty"`
	BlockedReason    any                        `json:"blockedReason,omitempty"`
	AgentPolicy      AgentPolicy                `json:"agentPolicy,omitempty"`
	CurrentStep      int                        `json:"currentStep,omitempty"`
	TotalSteps       int                        `json:"totalSteps,omitempty"`
	ReplanCount      int                        `json:"replanCount,omitempty"`
	StepExecution    AgentStepExecution         `json:"stepExecution,omitempty"`
	Confirmation     AgentConfirmation          `json:"confirmation,omitempty"`
	HumanHandoff     AgentHumanHandoff          `json:"humanHandoff,omitempty"`
	AllowedDomains   []string                   `json:"allowedDomains,omitempty"`
	Plan             AgentPlan                  `json:"plan,omitempty"`
	OperationId      any                        `json:"operationId,omitempty"`
	ExecutionResults []AgentToolExecutionResult `json:"executionResults,omitempty"`
	LastError        any                        `json:"lastError,omitempty"`
	SecurityEvents   []PromptSecurityEvent      `json:"securityEvents,omitempty"`
	CreatedAt        string                     `json:"createdAt,omitempty"`
	UpdatedAt        string                     `json:"updatedAt,omitempty"`
}

type AgentStepExecution struct {
	PendingStepId    any `json:"pendingStepId,omitempty"`
	PendingToolId    any `json:"pendingToolId,omitempty"`
	BaseStateVersion any `json:"baseStateVersion,omitempty"`
	BaseContentHash  any `json:"baseContentHash,omitempty"`
	Deadline         any `json:"deadline,omitempty"`
	LeaseUntil       any `json:"leaseUntil,omitempty"`
	ReplanReason     any `json:"replanReason,omitempty"`
}

type AgentConfirmation struct {
	ConfirmationId any `json:"confirmationId,omitempty"`
	Status         any `json:"status,omitempty"`
	ExpiresAt      any `json:"expiresAt,omitempty"`
	DecidedAt      any `json:"decidedAt,omitempty"`
	ActorId        any `json:"actorId,omitempty"`
	EvidenceHash   any `json:"evidenceHash,omitempty"`
}

type AgentHumanHandoff struct {
	RequestId any `json:"requestId,omitempty"`
	Status    any `json:"status,omitempty"`
	ExpiresAt any `json:"expiresAt,omitempty"`
	ActorId   any `json:"actorId,omitempty"`
}

type AgentPlan struct {
	IntentId     string          `json:"intentId,omitempty"`
	Steps        []AgentPlanStep `json:"steps,omitempty"`
	MaxActions   int             `json:"maxActions,omitempty"`
	ReplanBudget int             `json:"replanBudget,omitempty"`
	ExpiresAt    string          `json:"expiresAt,omitempty"`
}

type AgentPlanStep struct {
	StepId               string         `json:"stepId,omitempty"`
	ToolId               string         `json:"toolId,omitempty"`
	RiskClass            AgentRiskClass `json:"riskClass,omitempty"`
	TargetUrl            any            `json:"targetUrl,omitempty"`
	Input                any            `json:"input,omitempty"`
	Rationale            string         `json:"rationale,omitempty"`
	SupportingSources    []string       `json:"supportingSources,omitempty"`
	TrustFloor           string         `json:"trustFloor,omitempty"`
	TaintLabels          []string       `json:"taintLabels,omitempty"`
	RequiredConfirmation bool           `json:"requiredConfirmation,omitempty"`
	Strategy             string         `json:"strategy,omitempty"`
	RequiredStateQuality string         `json:"requiredStateQuality,omitempty"`
	Verification         string         `json:"verification,omitempty"`
	CapabilityTokenId    string         `json:"capabilityTokenId,omitempty"`
}

type AgentStepInput struct {
	TargetRef      any `json:"targetRef,omitempty"`
	TargetRevision any `json:"targetRevision,omitempty"`
	PayloadHash    any `json:"payloadHash,omitempty"`
	PayloadLength  any `json:"payloadLength,omitempty"`
	DataClass      any `json:"dataClass,omitempty"`
	ScrollDeltaY   any `json:"scrollDeltaY,omitempty"`
	WaitCondition  any `json:"waitCondition,omitempty"`
	TimeoutMs      any `json:"timeoutMs,omitempty"`
}

type AgentRiskClass string

const (
	AgentRiskClassR0READONLY      AgentRiskClass = "R0_READ_ONLY"
	AgentRiskClassR1LOWRISKCHANGE AgentRiskClass = "R1_LOW_RISK_CHANGE"
	AgentRiskClassR2DATACHANGE    AgentRiskClass = "R2_DATA_CHANGE"
	AgentRiskClassR3ACCOUNTCHANGE AgentRiskClass = "R3_ACCOUNT_CHANGE"
	AgentRiskClassR4FINANCIAL     AgentRiskClass = "R4_FINANCIAL"
	AgentRiskClassR5SECURITY      AgentRiskClass = "R5_SECURITY"
)

type AgentPolicy string

const (
	AgentPolicyDISABLED    AgentPolicy = "DISABLED"
	AgentPolicyRESTRICTED  AgentPolicy = "RESTRICTED"
	AgentPolicyBALANCED    AgentPolicy = "BALANCED"
	AgentPolicyINTERACTIVE AgentPolicy = "INTERACTIVE"
)

type AgentToolExecutionResult struct {
	StepId       string         `json:"stepId,omitempty"`
	ToolId       string         `json:"toolId,omitempty"`
	Status       string         `json:"status,omitempty"`
	ResultHash   string         `json:"resultHash,omitempty"`
	Output       map[string]any `json:"output,omitempty"`
	Verification string         `json:"verification,omitempty"`
	CompletedAt  string         `json:"completedAt,omitempty"`
}

type PromptSecurityEvent struct {
	EventId     string `json:"eventId,omitempty"`
	EventType   string `json:"eventType,omitempty"`
	Severity    string `json:"severity,omitempty"`
	Decision    string `json:"decision,omitempty"`
	RuleCode    string `json:"ruleCode,omitempty"`
	SourceType  string `json:"sourceType,omitempty"`
	ContentHash string `json:"contentHash,omitempty"`
	CreatedAt   string `json:"createdAt,omitempty"`
}

type CreateProfileRequest struct {
	ProfileId   string `json:"profileId,omitempty"`
	Name        string `json:"name,omitempty"`
	Description string `json:"description,omitempty"`
}

type Profile struct {
	ProfileId             string `json:"profileId,omitempty"`
	TenantId              string `json:"tenantId,omitempty"`
	Name                  string `json:"name,omitempty"`
	Description           any    `json:"description,omitempty"`
	LatestCheckpointId    any    `json:"latestCheckpointId,omitempty"`
	LatestCheckpointEpoch any    `json:"latestCheckpointEpoch,omitempty"`
	ProfileWriteEpoch     int64  `json:"profileWriteEpoch,omitempty"`
	CoreSizeBytes         int64  `json:"coreSizeBytes,omitempty"`
	CheckpointFileCount   int64  `json:"checkpointFileCount,omitempty"`
	RestoreStatus         string `json:"restoreStatus,omitempty"`
	State                 string `json:"state,omitempty"`
	CreatedAt             string `json:"createdAt,omitempty"`
	UpdatedAt             string `json:"updatedAt,omitempty"`
	LastCheckpointAt      any    `json:"lastCheckpointAt,omitempty"`
}

type ProfileListResponse struct {
	Items []Profile `json:"items,omitempty"`
	Total int       `json:"total,omitempty"`
}

type ProfileImport struct {
	ImportId            string `json:"importId,omitempty"`
	OperationId         string `json:"operationId,omitempty"`
	ProfileId           string `json:"profileId,omitempty"`
	ProfileName         string `json:"profileName,omitempty"`
	RuntimeBuildId      string `json:"runtimeBuildId,omitempty"`
	ArchiveSha256       string `json:"archiveSha256,omitempty"`
	ArchiveSizeBytes    int64  `json:"archiveSizeBytes,omitempty"`
	State               string `json:"state,omitempty"`
	NodeId              any    `json:"nodeId,omitempty"`
	CheckpointId        string `json:"checkpointId,omitempty"`
	CheckpointEpoch     any    `json:"checkpointEpoch,omitempty"`
	ProfileWriteEpoch   any    `json:"profileWriteEpoch,omitempty"`
	CoreSizeBytes       any    `json:"coreSizeBytes,omitempty"`
	CheckpointFileCount any    `json:"checkpointFileCount,omitempty"`
	ErrorCode           any    `json:"errorCode,omitempty"`
	RequestId           string `json:"requestId,omitempty"`
	CreatedAt           string `json:"createdAt,omitempty"`
	UpdatedAt           string `json:"updatedAt,omitempty"`
	CompletedAt         any    `json:"completedAt,omitempty"`
}

type ProfileImportListResponse struct {
	Items []ProfileImport `json:"items,omitempty"`
	Total int             `json:"total,omitempty"`
}

type ProxyProvider struct {
	ProviderId            string   `json:"providerId,omitempty"`
	Type                  string   `json:"type,omitempty"`
	Endpoint              string   `json:"endpoint,omitempty"`
	ExpectedExitIp        string   `json:"expectedExitIp,omitempty"`
	DirectFallbackAllowed bool     `json:"directFallbackAllowed,omitempty"`
	State                 string   `json:"state,omitempty"`
	Regions               []string `json:"regions,omitempty"`
	CostPerGibUsd         float64  `json:"costPerGibUsd,omitempty"`
	ReputationScore       int      `json:"reputationScore,omitempty"`
	MaxConcurrentSessions int      `json:"maxConcurrentSessions,omitempty"`
}

type ProxyAllocation struct {
	AllocationId string `json:"allocationId,omitempty"`
	SessionId    string `json:"sessionId,omitempty"`
	ProviderId   string `json:"providerId,omitempty"`
	Protocol     string `json:"protocol,omitempty"`
	State        string `json:"state,omitempty"`
	ExitIp       any    `json:"exitIp,omitempty"`
	Country      any    `json:"country,omitempty"`
	Asn          any    `json:"asn,omitempty"`
	AllocatedAt  string `json:"allocatedAt,omitempty"`
	VerifiedAt   any    `json:"verifiedAt,omitempty"`
	ReleasedAt   any    `json:"releasedAt,omitempty"`
	UpdatedAt    string `json:"updatedAt,omitempty"`
}

type ProxyOverview struct {
	Provider    ProxyProvider     `json:"provider,omitempty"`
	Providers   []ProxyProvider   `json:"providers,omitempty"`
	Allocations []ProxyAllocation `json:"allocations,omitempty"`
	Total       int               `json:"total,omitempty"`
}

type ProxyBindingHealth string

const (
	ProxyBindingHealthUNVERIFIED ProxyBindingHealth = "UNVERIFIED"
	ProxyBindingHealthHEALTHY    ProxyBindingHealth = "HEALTHY"
	ProxyBindingHealthUNHEALTHY  ProxyBindingHealth = "UNHEALTHY"
	ProxyBindingHealthDISABLED   ProxyBindingHealth = "DISABLED"
)

type ProxyBindingRequest struct {
	Name            string `json:"name,omitempty"`
	Description     any    `json:"description,omitempty"`
	ProviderId      string `json:"providerId,omitempty"`
	Region          any    `json:"region,omitempty"`
	ExpectedExitIp  string `json:"expectedExitIp,omitempty"`
	CredentialRef   any    `json:"credentialRef,omitempty"`
	Enabled         bool   `json:"enabled,omitempty"`
	ExpectedVersion any    `json:"expectedVersion,omitempty"`
}

type ProxyBinding struct {
	BindingProfileId        string             `json:"bindingProfileId,omitempty"`
	Name                    string             `json:"name,omitempty"`
	Description             any                `json:"description,omitempty"`
	ProviderId              string             `json:"providerId,omitempty"`
	Region                  any                `json:"region,omitempty"`
	ExpectedExitIp          string             `json:"expectedExitIp,omitempty"`
	CredentialConfigured    bool               `json:"credentialConfigured,omitempty"`
	Enabled                 bool               `json:"enabled,omitempty"`
	HealthState             ProxyBindingHealth `json:"healthState,omitempty"`
	LastVerifiedExitIp      any                `json:"lastVerifiedExitIp,omitempty"`
	LastHealthCheckedAt     any                `json:"lastHealthCheckedAt,omitempty"`
	LastFailureReason       any                `json:"lastFailureReason,omitempty"`
	ProbeSampleCount        int64              `json:"probeSampleCount,omitempty"`
	ProbeSuccessRatePercent any                `json:"probeSuccessRatePercent,omitempty"`
	LatencyEwmaMs           any                `json:"latencyEwmaMs,omitempty"`
	QualityScore            any                `json:"qualityScore,omitempty"`
	CostPerGibUsd           float64            `json:"costPerGibUsd,omitempty"`
	ReputationScore         int                `json:"reputationScore,omitempty"`
	MaxConcurrentSessions   int                `json:"maxConcurrentSessions,omitempty"`
	AutomaticRoutingReady   bool               `json:"automaticRoutingReady,omitempty"`
	HealthFreshUntil        any                `json:"healthFreshUntil,omitempty"`
	ConsecutiveFailures     int                `json:"consecutiveFailures,omitempty"`
	Version                 int64              `json:"version,omitempty"`
	CreatedBy               string             `json:"createdBy,omitempty"`
	CreatedAt               string             `json:"createdAt,omitempty"`
	UpdatedAt               string             `json:"updatedAt,omitempty"`
}

type ProxyBindingList struct {
	Items []ProxyBinding `json:"items,omitempty"`
	Total int            `json:"total,omitempty"`
}

type ProxyRoutingCandidateScore struct {
	BindingProfileId      string  `json:"bindingProfileId,omitempty"`
	ProviderId            string  `json:"providerId,omitempty"`
	RoutingScore          float64 `json:"routingScore,omitempty"`
	QualityScore          int     `json:"qualityScore,omitempty"`
	ReputationScore       int     `json:"reputationScore,omitempty"`
	CostPerGibUsd         float64 `json:"costPerGibUsd,omitempty"`
	CostScore             float64 `json:"costScore,omitempty"`
	RegionScore           float64 `json:"regionScore,omitempty"`
	HeadroomScore         float64 `json:"headroomScore,omitempty"`
	ActiveReservations    int     `json:"activeReservations,omitempty"`
	MaxConcurrentSessions int     `json:"maxConcurrentSessions,omitempty"`
}

type ProxyRoutingDecision struct {
	SessionId             string                       `json:"sessionId,omitempty"`
	BindingProfileId      string                       `json:"bindingProfileId,omitempty"`
	ProviderId            string                       `json:"providerId,omitempty"`
	SelectionMode         string                       `json:"selectionMode,omitempty"`
	RoutingScore          any                          `json:"routingScore,omitempty"`
	QualityScore          any                          `json:"qualityScore,omitempty"`
	ReputationScore       any                          `json:"reputationScore,omitempty"`
	CostPerGibUsd         any                          `json:"costPerGibUsd,omitempty"`
	ActiveReservations    any                          `json:"activeReservations,omitempty"`
	MaxConcurrentSessions any                          `json:"maxConcurrentSessions,omitempty"`
	CandidateCount        int                          `json:"candidateCount,omitempty"`
	CandidateScores       []ProxyRoutingCandidateScore `json:"candidateScores,omitempty"`
	SelectedAt            string                       `json:"selectedAt,omitempty"`
}

type ProxyRebindRequest struct {
	TargetBindingProfileId string `json:"targetBindingProfileId,omitempty"`
	Reason                 string `json:"reason,omitempty"`
}

type ProxyRebindOperation struct {
	WorkflowId  string `json:"workflowId,omitempty"`
	OperationId string `json:"operationId,omitempty"`
	Phase       string `json:"phase,omitempty"`
	CreatedAt   string `json:"createdAt,omitempty"`
}

type ProxyRebind struct {
	WorkflowId             string `json:"workflowId,omitempty"`
	SessionId              string `json:"sessionId,omitempty"`
	SourceBindingProfileId any    `json:"sourceBindingProfileId,omitempty"`
	TargetBindingProfileId string `json:"targetBindingProfileId,omitempty"`
	TargetBindingVersion   int64  `json:"targetBindingVersion,omitempty"`
	HibernateOperationId   any    `json:"hibernateOperationId,omitempty"`
	RestoreOperationId     any    `json:"restoreOperationId,omitempty"`
	ResyncRequestId        any    `json:"resyncRequestId,omitempty"`
	Phase                  string `json:"phase,omitempty"`
	RecoveryResult         any    `json:"recoveryResult,omitempty"`
	FailureReason          any    `json:"failureReason,omitempty"`
	RequestedBy            string `json:"requestedBy,omitempty"`
	Reason                 string `json:"reason,omitempty"`
	RequestId              string `json:"requestId,omitempty"`
	CreatedAt              string `json:"createdAt,omitempty"`
	UpdatedAt              string `json:"updatedAt,omitempty"`
	CompletedAt            any    `json:"completedAt,omitempty"`
}

type SessionState string

const (
	SessionStateCREATED     SessionState = "CREATED"
	SessionStateSTARTING    SessionState = "STARTING"
	SessionStateRUNNING     SessionState = "RUNNING"
	SessionStateDEGRADED    SessionState = "DEGRADED"
	SessionStateHIBERNATING SessionState = "HIBERNATING"
	SessionStateHIBERNATED  SessionState = "HIBERNATED"
	SessionStateRECOVERING  SessionState = "RECOVERING"
	SessionStateTERMINATING SessionState = "TERMINATING"
	SessionStateTERMINATED  SessionState = "TERMINATED"
	SessionStateFAILED      SessionState = "FAILED"
)

type ResourceTemplate string

const (
	ResourceTemplateSuspendedV1      ResourceTemplate = "suspended-v1"
	ResourceTemplateStandardLiteV1   ResourceTemplate = "standard-lite-v1"
	ResourceTemplateStandardV1       ResourceTemplate = "standard-v1"
	ResourceTemplateInteractiveV1    ResourceTemplate = "interactive-v1"
	ResourceTemplateHeavyV1          ResourceTemplate = "heavy-v1"
	ResourceTemplateNativeStandardV1 ResourceTemplate = "native-standard-v1"
)

type RegisterBrowserNodeRequest struct {
	Region              string            `json:"region,omitempty"`
	GrpcTarget          string            `json:"grpcTarget,omitempty"`
	CertifiedCpuMillis  int               `json:"certifiedCpuMillis,omitempty"`
	CertifiedMemoryMib  int               `json:"certifiedMemoryMib,omitempty"`
	CertifiedPidCount   int               `json:"certifiedPidCount,omitempty"`
	CertifiedGpuSlots   int               `json:"certifiedGpuSlots,omitempty"`
	CertifiedMediaSlots int               `json:"certifiedMediaSlots,omitempty"`
	SafetyMarginPercent int               `json:"safetyMarginPercent,omitempty"`
	MaxSessions         int               `json:"maxSessions,omitempty"`
	SupportsDesktop     bool              `json:"supportsDesktop,omitempty"`
	SupportsGpu         bool              `json:"supportsGpu,omitempty"`
	SupportsMedia       bool              `json:"supportsMedia,omitempty"`
	SupportsNativeOs    bool              `json:"supportsNativeOs,omitempty"`
	IsolationCapable    bool              `json:"isolationCapable,omitempty"`
	Labels              map[string]string `json:"labels,omitempty"`
}

type RecordNodePressureRequest struct {
	MemoryPsiSomeAvg10 float64 `json:"memoryPsiSomeAvg10,omitempty"`
	MemoryPsiFullAvg10 float64 `json:"memoryPsiFullAvg10,omitempty"`
	CpuPsiSomeAvg10    float64 `json:"cpuPsiSomeAvg10,omitempty"`
	IoPsiFullAvg10     float64 `json:"ioPsiFullAvg10,omitempty"`
	Reason             any     `json:"reason,omitempty"`
}

type BrowserNode struct {
	NodeId              string            `json:"nodeId,omitempty"`
	Region              string            `json:"region,omitempty"`
	GrpcTarget          string            `json:"grpcTarget,omitempty"`
	LifecycleState      string            `json:"lifecycleState,omitempty"`
	AdmissionState      string            `json:"admissionState,omitempty"`
	CertifiedCpuMillis  int               `json:"certifiedCpuMillis,omitempty"`
	CertifiedMemoryMib  int               `json:"certifiedMemoryMib,omitempty"`
	CertifiedPidCount   int               `json:"certifiedPidCount,omitempty"`
	CertifiedGpuSlots   int               `json:"certifiedGpuSlots,omitempty"`
	CertifiedMediaSlots int               `json:"certifiedMediaSlots,omitempty"`
	SafetyMarginPercent int               `json:"safetyMarginPercent,omitempty"`
	ReservedCpuMillis   int               `json:"reservedCpuMillis,omitempty"`
	ReservedMemoryMib   int               `json:"reservedMemoryMib,omitempty"`
	ReservedPidCount    int               `json:"reservedPidCount,omitempty"`
	ReservedGpuSlots    int               `json:"reservedGpuSlots,omitempty"`
	ReservedMediaSlots  int               `json:"reservedMediaSlots,omitempty"`
	ActiveSessions      int               `json:"activeSessions,omitempty"`
	MaxSessions         int               `json:"maxSessions,omitempty"`
	MemoryPsiSomeAvg10  float64           `json:"memoryPsiSomeAvg10,omitempty"`
	MemoryPsiFullAvg10  float64           `json:"memoryPsiFullAvg10,omitempty"`
	CpuPsiSomeAvg10     float64           `json:"cpuPsiSomeAvg10,omitempty"`
	IoPsiFullAvg10      float64           `json:"ioPsiFullAvg10,omitempty"`
	PressureState       string            `json:"pressureState,omitempty"`
	PressureReason      any               `json:"pressureReason,omitempty"`
	SupportsDesktop     bool              `json:"supportsDesktop,omitempty"`
	SupportsGpu         bool              `json:"supportsGpu,omitempty"`
	SupportsMedia       bool              `json:"supportsMedia,omitempty"`
	SupportsNativeOs    bool              `json:"supportsNativeOs,omitempty"`
	IsolationCapable    bool              `json:"isolationCapable,omitempty"`
	Labels              map[string]string `json:"labels,omitempty"`
	LastHeartbeatAt     string            `json:"lastHeartbeatAt,omitempty"`
	UpdatedAt           string            `json:"updatedAt,omitempty"`
}

type BrowserNodeListResponse struct {
	Items []BrowserNode `json:"items,omitempty"`
	Total int           `json:"total,omitempty"`
}

type UpsertExtensionProfileRequest struct {
	DisplayName         string  `json:"displayName,omitempty"`
	StaticCpuWeight     int     `json:"staticCpuWeight,omitempty"`
	StaticMemoryWeight  int     `json:"staticMemoryWeight,omitempty"`
	StartupWeight       int     `json:"startupWeight,omitempty"`
	PageInjectionWeight int     `json:"pageInjectionWeight,omitempty"`
	ServiceWorkerWeight int     `json:"serviceWorkerWeight,omitempty"`
	CryptoWeight        int     `json:"cryptoWeight,omitempty"`
	NetworkWeight       int     `json:"networkWeight,omitempty"`
	ObservedMultiplier  float64 `json:"observedMultiplier,omitempty"`
	Confidence          float64 `json:"confidence,omitempty"`
	ProfileState        string  `json:"profileState,omitempty"`
	Web3                bool    `json:"web3,omitempty"`
	ServiceWorker       bool    `json:"serviceWorker,omitempty"`
	Crypto              bool    `json:"crypto,omitempty"`
	Privileged          bool    `json:"privileged,omitempty"`
}

type ExtensionProfile struct {
	ExtensionId             string  `json:"extensionId,omitempty"`
	DisplayName             string  `json:"displayName,omitempty"`
	StaticCpuWeight         int     `json:"staticCpuWeight,omitempty"`
	StaticMemoryWeight      int     `json:"staticMemoryWeight,omitempty"`
	StartupWeight           int     `json:"startupWeight,omitempty"`
	PageInjectionWeight     int     `json:"pageInjectionWeight,omitempty"`
	ServiceWorkerWeight     int     `json:"serviceWorkerWeight,omitempty"`
	CryptoWeight            int     `json:"cryptoWeight,omitempty"`
	NetworkWeight           int     `json:"networkWeight,omitempty"`
	ObservedMultiplier      float64 `json:"observedMultiplier,omitempty"`
	Confidence              float64 `json:"confidence,omitempty"`
	ProfileState            string  `json:"profileState,omitempty"`
	Web3                    bool    `json:"web3,omitempty"`
	ServiceWorker           bool    `json:"serviceWorker,omitempty"`
	Crypto                  bool    `json:"crypto,omitempty"`
	Privileged              bool    `json:"privileged,omitempty"`
	Samples                 int64   `json:"samples,omitempty"`
	P95CpuMillis            any     `json:"p95CpuMillis,omitempty"`
	P95MemoryMib            any     `json:"p95MemoryMib,omitempty"`
	LastProfiledAt          any     `json:"lastProfiledAt,omitempty"`
	SamplingTier            string  `json:"samplingTier,omitempty"`
	SamplingCpuBudgetMillis int     `json:"samplingCpuBudgetMillis,omitempty"`
	NextSampleAt            any     `json:"nextSampleAt,omitempty"`
	UpdatedAt               string  `json:"updatedAt,omitempty"`
}

type RecordExtensionSampleRequest struct {
	NodeId          string `json:"nodeId,omitempty"`
	CpuMillis       int    `json:"cpuMillis,omitempty"`
	MemoryMib       int    `json:"memoryMib,omitempty"`
	CgroupPsiBurst  bool   `json:"cgroupPsiBurst,omitempty"`
	SampleCpuMillis int    `json:"sampleCpuMillis,omitempty"`
	ObservedAt      string `json:"observedAt,omitempty"`
}

type ExtensionProfileListResponse struct {
	Items []ExtensionProfile `json:"items,omitempty"`
	Total int                `json:"total,omitempty"`
}

type BrowserPlacement struct {
	SessionId                      string           `json:"sessionId,omitempty"`
	TenantId                       string           `json:"tenantId,omitempty"`
	NodeId                         string           `json:"nodeId,omitempty"`
	RequestedTemplate              ResourceTemplate `json:"requestedTemplate,omitempty"`
	ResolvedTemplate               ResourceTemplate `json:"resolvedTemplate,omitempty"`
	ExtensionIds                   []string         `json:"extensionIds,omitempty"`
	UnknownExtensionCount          int              `json:"unknownExtensionCount,omitempty"`
	CpuMillis                      int              `json:"cpuMillis,omitempty"`
	MemoryRequestMib               int              `json:"memoryRequestMib,omitempty"`
	MemoryLimitMib                 int              `json:"memoryLimitMib,omitempty"`
	PidLimit                       int              `json:"pidLimit,omitempty"`
	TabBudget                      int              `json:"tabBudget,omitempty"`
	StateCollectorBudgetPercent    int              `json:"stateCollectorBudgetPercent,omitempty"`
	RemoteDesktopBitrateKbps       int              `json:"remoteDesktopBitrateKbps,omitempty"`
	ExtensionCpuWeight             int              `json:"extensionCpuWeight,omitempty"`
	RequiresDesktop                bool             `json:"requiresDesktop,omitempty"`
	RequiresGpu                    bool             `json:"requiresGpu,omitempty"`
	RequiresNativeOs               bool             `json:"requiresNativeOs,omitempty"`
	RequiresIsolation              bool             `json:"requiresIsolation,omitempty"`
	RequiresMedia                  bool             `json:"requiresMedia,omitempty"`
	MediaSlots                     int              `json:"mediaSlots,omitempty"`
	MediaEncoderSlots              int              `json:"mediaEncoderSlots,omitempty"`
	BackgroundTabsFrozen           bool             `json:"backgroundTabsFrozen,omitempty"`
	NewTabsBlocked                 bool             `json:"newTabsBlocked,omitempty"`
	PausedExtensionIds             []string         `json:"pausedExtensionIds,omitempty"`
	SuccessTraceSamplePercent      int              `json:"successTraceSamplePercent,omitempty"`
	SuccessScreenshotSamplePercent int              `json:"successScreenshotSamplePercent,omitempty"`
	ObserverFrameRateFps           int              `json:"observerFrameRateFps,omitempty"`
	VideoRecordingRequested        bool             `json:"videoRecordingRequested,omitempty"`
	VideoRecordingEnabled          bool             `json:"videoRecordingEnabled,omitempty"`
	MediaBitrateKbps               int              `json:"mediaBitrateKbps,omitempty"`
	PlacementScore                 int              `json:"placementScore,omitempty"`
	State                          string           `json:"state,omitempty"`
	ReasonCodes                    []string         `json:"reasonCodes,omitempty"`
	ReservedAt                     string           `json:"reservedAt,omitempty"`
	ActivatedAt                    any              `json:"activatedAt,omitempty"`
	ReleasedAt                     any              `json:"releasedAt,omitempty"`
}

type BrowserState struct {
	SessionId            string              `json:"sessionId,omitempty"`
	ContextEpoch         int64               `json:"contextEpoch,omitempty"`
	StateVersion         int64               `json:"stateVersion,omitempty"`
	TargetRevision       int64               `json:"targetRevision,omitempty"`
	Url                  string              `json:"url,omitempty"`
	Title                string              `json:"title,omitempty"`
	StateHash            string              `json:"stateHash,omitempty"`
	StateQuality         string              `json:"stateQuality,omitempty"`
	DocumentReadyState   string              `json:"documentReadyState,omitempty"`
	NetworkQuietMillis   int64               `json:"networkQuietMillis,omitempty"`
	NetworkEvidenceFresh bool                `json:"networkEvidenceFresh,omitempty"`
	Targets              []InteractiveTarget `json:"targets,omitempty"`
}

type StateResyncRequest struct {
	Mode    string `json:"mode,omitempty"`
	RootRef any    `json:"rootRef,omitempty"`
	Reason  any    `json:"reason,omitempty"`
}

type StateResyncResponse struct {
	RequestId string `json:"requestId,omitempty"`
	Mode      string `json:"mode,omitempty"`
	State     string `json:"state,omitempty"`
}

type InteractiveTarget struct {
	TargetRef string `json:"targetRef,omitempty"`
	Role      string `json:"role,omitempty"`
	Name      any    `json:"name,omitempty"`
	Bounds    any    `json:"bounds,omitempty"`
	Enabled   bool   `json:"enabled,omitempty"`
	Visible   bool   `json:"visible,omitempty"`
}

type TargetBounds struct {
	X      float64 `json:"x,omitempty"`
	Y      float64 `json:"y,omitempty"`
	Width  float64 `json:"width,omitempty"`
	Height float64 `json:"height,omitempty"`
}

type RecoveryTargetIndicator struct {
	Role string `json:"role,omitempty"`
	Name string `json:"name,omitempty"`
}

type ProviderEvidenceRequirement struct {
	Type              string `json:"type,omitempty"`
	Key               string `json:"key,omitempty"`
	ProviderId        string `json:"providerId,omitempty"`
	ExpectedValueHash string `json:"expectedValueHash,omitempty"`
	MaxAgeSeconds     int    `json:"maxAgeSeconds,omitempty"`
}

type UpsertRecoveryContractRequest struct {
	ExpectedVersion           int64                         `json:"expectedVersion,omitempty"`
	ExpectedOrigins           []string                      `json:"expectedOrigins,omitempty"`
	ReadyRoutePrefixes        []string                      `json:"readyRoutePrefixes,omitempty"`
	LoginRoutePrefixes        []string                      `json:"loginRoutePrefixes,omitempty"`
	RequiredTargets           []RecoveryTargetIndicator     `json:"requiredTargets,omitempty"`
	LoginTargets              []RecoveryTargetIndicator     `json:"loginTargets,omitempty"`
	PermissionDeniedTargets   []RecoveryTargetIndicator     `json:"permissionDeniedTargets,omitempty"`
	AccountMismatchTargets    []RecoveryTargetIndicator     `json:"accountMismatchTargets,omitempty"`
	RequiredExtensionIds      []string                      `json:"requiredExtensionIds,omitempty"`
	RequiredProviderEvidence  []ProviderEvidenceRequirement `json:"requiredProviderEvidence,omitempty"`
	RequireDocumentComplete   bool                          `json:"requireDocumentComplete,omitempty"`
	MinimumNetworkQuietMillis int                           `json:"minimumNetworkQuietMillis,omitempty"`
	TransientBlockerTargets   []RecoveryTargetIndicator     `json:"transientBlockerTargets,omitempty"`
	AllowDepthLimited         bool                          `json:"allowDepthLimited,omitempty"`
	RecoveryAction            string                        `json:"recoveryAction,omitempty"`
	RecoveryExtensionId       any                           `json:"recoveryExtensionId,omitempty"`
	MaximumAutoRecovery       int                           `json:"maximumAutoRecovery,omitempty"`
	Enabled                   bool                          `json:"enabled,omitempty"`
}

type RecoveryContract struct {
	ContractId                string                        `json:"contractId,omitempty"`
	ApplicationId             string                        `json:"applicationId,omitempty"`
	Version                   int64                         `json:"version,omitempty"`
	ExpectedOrigins           []string                      `json:"expectedOrigins,omitempty"`
	ReadyRoutePrefixes        []string                      `json:"readyRoutePrefixes,omitempty"`
	LoginRoutePrefixes        []string                      `json:"loginRoutePrefixes,omitempty"`
	RequiredTargets           []RecoveryTargetIndicator     `json:"requiredTargets,omitempty"`
	LoginTargets              []RecoveryTargetIndicator     `json:"loginTargets,omitempty"`
	PermissionDeniedTargets   []RecoveryTargetIndicator     `json:"permissionDeniedTargets,omitempty"`
	AccountMismatchTargets    []RecoveryTargetIndicator     `json:"accountMismatchTargets,omitempty"`
	RequiredExtensionIds      []string                      `json:"requiredExtensionIds,omitempty"`
	RequiredProviderEvidence  []ProviderEvidenceRequirement `json:"requiredProviderEvidence,omitempty"`
	RequireDocumentComplete   bool                          `json:"requireDocumentComplete,omitempty"`
	MinimumNetworkQuietMillis int                           `json:"minimumNetworkQuietMillis,omitempty"`
	TransientBlockerTargets   []RecoveryTargetIndicator     `json:"transientBlockerTargets,omitempty"`
	AllowDepthLimited         bool                          `json:"allowDepthLimited,omitempty"`
	RecoveryAction            string                        `json:"recoveryAction,omitempty"`
	RecoveryExtensionId       any                           `json:"recoveryExtensionId,omitempty"`
	MaximumAutoRecovery       int                           `json:"maximumAutoRecovery,omitempty"`
	Enabled                   bool                          `json:"enabled,omitempty"`
	ApprovalState             string                        `json:"approvalState,omitempty"`
	ApprovalId                any                           `json:"approvalId,omitempty"`
	ApprovalRequestedBy       any                           `json:"approvalRequestedBy,omitempty"`
	ApprovedBy                any                           `json:"approvedBy,omitempty"`
	ApprovalRequestedAt       any                           `json:"approvalRequestedAt,omitempty"`
	ApprovalDecidedAt         any                           `json:"approvalDecidedAt,omitempty"`
	CreatedAt                 string                        `json:"createdAt,omitempty"`
	UpdatedAt                 string                        `json:"updatedAt,omitempty"`
}

type RecoveryContractListResponse struct {
	Items []RecoveryContract `json:"items,omitempty"`
	Total int64              `json:"total,omitempty"`
}

type RecoveryContractRevisionListResponse struct {
	Items          []RecoveryContract `json:"items,omitempty"`
	Total          int64              `json:"total,omitempty"`
	CurrentVersion int64              `json:"currentVersion,omitempty"`
}

type RecoveryContractFieldChange struct {
	Field       string `json:"field,omitempty"`
	ChangeType  string `json:"changeType,omitempty"`
	BeforeValue string `json:"beforeValue,omitempty"`
	AfterValue  string `json:"afterValue,omitempty"`
}

type RecoveryContractDiff struct {
	ContractId    string                        `json:"contractId,omitempty"`
	ApplicationId string                        `json:"applicationId,omitempty"`
	FromVersion   int64                         `json:"fromVersion,omitempty"`
	ToVersion     int64                         `json:"toVersion,omitempty"`
	Changes       []RecoveryContractFieldChange `json:"changes,omitempty"`
	Total         int64                         `json:"total,omitempty"`
}

type RestoreRecoveryContractRevisionRequest struct {
	ExpectedCurrentVersion int64  `json:"expectedCurrentVersion,omitempty"`
	SourceContractVersion  int64  `json:"sourceContractVersion,omitempty"`
	Reason                 string `json:"reason,omitempty"`
}

type RequestRecoveryContractApprovalRequest struct {
	ExpectedVersion int64  `json:"expectedVersion,omitempty"`
	Reason          string `json:"reason,omitempty"`
}

type RecoveryContractApproval struct {
	ApprovalId      string `json:"approvalId,omitempty"`
	ContractId      string `json:"contractId,omitempty"`
	ApplicationId   string `json:"applicationId,omitempty"`
	ContractVersion int64  `json:"contractVersion,omitempty"`
	Reason          string `json:"reason,omitempty"`
	State           string `json:"state,omitempty"`
	RequestedBy     string `json:"requestedBy,omitempty"`
	ApprovedBy      any    `json:"approvedBy,omitempty"`
	RejectedBy      any    `json:"rejectedBy,omitempty"`
	RequestedAt     string `json:"requestedAt,omitempty"`
	DecidedAt       any    `json:"decidedAt,omitempty"`
	EvidenceHash    any    `json:"evidenceHash,omitempty"`
}

type SessionApplicationBinding struct {
	SessionId              string `json:"sessionId,omitempty"`
	ApplicationId          string `json:"applicationId,omitempty"`
	ContractId             string `json:"contractId,omitempty"`
	ContractVersion        int64  `json:"contractVersion,omitempty"`
	LatestContractVersion  int64  `json:"latestContractVersion,omitempty"`
	LatestApprovalState    string `json:"latestApprovalState,omitempty"`
	CurrentContractEnabled bool   `json:"currentContractEnabled,omitempty"`
	UpgradeAvailable       bool   `json:"upgradeAvailable,omitempty"`
	BoundAt                string `json:"boundAt,omitempty"`
}

type RebindSessionApplicationRequest struct {
	ExpectedCurrentVersion int64 `json:"expectedCurrentVersion,omitempty"`
	TargetContractVersion  int64 `json:"targetContractVersion,omitempty"`
}

type SessionApplicationRebind struct {
	OperationId             string `json:"operationId,omitempty"`
	SessionId               string `json:"sessionId,omitempty"`
	ApplicationId           string `json:"applicationId,omitempty"`
	ContractId              string `json:"contractId,omitempty"`
	PreviousContractVersion int64  `json:"previousContractVersion,omitempty"`
	TargetContractVersion   int64  `json:"targetContractVersion,omitempty"`
	State                   string `json:"state,omitempty"`
	RequestId               string `json:"requestId,omitempty"`
	CreatedAt               string `json:"createdAt,omitempty"`
	CompletedAt             string `json:"completedAt,omitempty"`
}

type BusinessRecoveryValidation struct {
	ValidationId    string   `json:"validationId,omitempty"`
	SessionId       string   `json:"sessionId,omitempty"`
	ApplicationId   any      `json:"applicationId,omitempty"`
	ContractVersion any      `json:"contractVersion,omitempty"`
	ContextEpoch    int64    `json:"contextEpoch,omitempty"`
	StateVersion    int64    `json:"stateVersion,omitempty"`
	Verdict         string   `json:"verdict,omitempty"`
	Ready           bool     `json:"ready,omitempty"`
	Evidence        []string `json:"evidence,omitempty"`
	Source          string   `json:"source,omitempty"`
	RequestId       string   `json:"requestId,omitempty"`
	EvaluatedAt     string   `json:"evaluatedAt,omitempty"`
}

type SubmitProviderEvidenceRequest struct {
	ContextEpoch      int64  `json:"contextEpoch,omitempty"`
	StateVersion      int64  `json:"stateVersion,omitempty"`
	Type              string `json:"type,omitempty"`
	Key               string `json:"key,omitempty"`
	ProviderId        string `json:"providerId,omitempty"`
	ObservedValueHash string `json:"observedValueHash,omitempty"`
	Outcome           string `json:"outcome,omitempty"`
	ProviderReference string `json:"providerReference,omitempty"`
	ObservedAt        string `json:"observedAt,omitempty"`
}

type ProviderEvidence struct {
	EvidenceId            string `json:"evidenceId,omitempty"`
	SessionId             string `json:"sessionId,omitempty"`
	ApplicationId         string `json:"applicationId,omitempty"`
	ContractVersion       int64  `json:"contractVersion,omitempty"`
	ContextEpoch          int64  `json:"contextEpoch,omitempty"`
	StateVersion          int64  `json:"stateVersion,omitempty"`
	Type                  string `json:"type,omitempty"`
	Key                   string `json:"key,omitempty"`
	ProviderId            string `json:"providerId,omitempty"`
	Outcome               string `json:"outcome,omitempty"`
	ValueHashMatched      bool   `json:"valueHashMatched,omitempty"`
	ProviderReferenceHash string `json:"providerReferenceHash,omitempty"`
	AdapterActorId        string `json:"adapterActorId,omitempty"`
	RequestId             string `json:"requestId,omitempty"`
	ObservedAt            string `json:"observedAt,omitempty"`
	ExpiresAt             string `json:"expiresAt,omitempty"`
	CreatedAt             string `json:"createdAt,omitempty"`
}

type ProviderEvidenceListResponse struct {
	Items []ProviderEvidence `json:"items,omitempty"`
	Total int64              `json:"total,omitempty"`
}

type CreateSessionRequest struct {
	TenantId              string                `json:"tenantId,omitempty"`
	ProfileId             string                `json:"profileId,omitempty"`
	RuntimeBuildId        string                `json:"runtimeBuildId,omitempty"`
	ApplicationId         string                `json:"applicationId,omitempty"`
	GroupId               string                `json:"groupId,omitempty"`
	TagIds                []string              `json:"tagIds,omitempty"`
	Region                string                `json:"region,omitempty"`
	ProxyBindingProfileId string                `json:"proxyBindingProfileId,omitempty"`
	ResourcePolicy        ResourcePolicyRequest `json:"resourcePolicy,omitempty"`
	RequestedTabs         int                   `json:"requestedTabs,omitempty"`
	AgentActionsPerMinute int                   `json:"agentActionsPerMinute,omitempty"`
	RemoteDesktop         bool                  `json:"remoteDesktop,omitempty"`
	HumanTakeoverEnabled  bool                  `json:"humanTakeoverEnabled,omitempty"`
	AgentPolicy           AgentPolicy           `json:"agentPolicy,omitempty"`
	Web3Workload          bool                  `json:"web3Workload,omitempty"`
	MediaWorkload         bool                  `json:"mediaWorkload,omitempty"`
	RequestedMediaStreams int                   `json:"requestedMediaStreams,omitempty"`
	MediaBitrateKbps      int                   `json:"mediaBitrateKbps,omitempty"`
	VideoRecording        bool                  `json:"videoRecording,omitempty"`
	ExtensionIds          []string              `json:"extensionIds,omitempty"`
	Metadata              map[string]string     `json:"metadata,omitempty"`
}

type CreateSessionResponse struct {
	SessionId      string         `json:"sessionId,omitempty"`
	OperationId    any            `json:"operationId,omitempty"`
	State          string         `json:"state,omitempty"`
	ResourcePolicy ResourcePolicy `json:"resourcePolicy,omitempty"`
	Context        SessionContext `json:"context,omitempty"`
}

type ResourcePolicyRequest struct {
	Mode                              string               `json:"mode,omitempty"`
	OnMaximumReached                  MaximumReachedPolicy `json:"onMaximumReached,omitempty"`
	AllowMigration                    bool                 `json:"allowMigration,omitempty"`
	AllowHibernate                    bool                 `json:"allowHibernate,omitempty"`
	BlockMigrationDuringHumanTakeover bool                 `json:"blockMigrationDuringHumanTakeover,omitempty"`
	ExecutionEnvironment              ExecutionEnvironment `json:"executionEnvironment,omitempty"`
	MinimumTemplate                   string               `json:"minimumTemplate,omitempty"`
	MaximumCpuMillis                  int                  `json:"maximumCpuMillis,omitempty"`
	MaximumMemoryMib                  int                  `json:"maximumMemoryMib,omitempty"`
	MaximumCostPerHour                float64              `json:"maximumCostPerHour,omitempty"`
	ScaleUpWindowSeconds              int                  `json:"scaleUpWindowSeconds,omitempty"`
	ScaleDownWindowSeconds            int                  `json:"scaleDownWindowSeconds,omitempty"`
	AdjustmentCooldownSeconds         int                  `json:"adjustmentCooldownSeconds,omitempty"`
}

type ResourcePolicy struct {
	Mode                              string               `json:"mode,omitempty"`
	OnMaximumReached                  MaximumReachedPolicy `json:"onMaximumReached,omitempty"`
	AllowMigration                    bool                 `json:"allowMigration,omitempty"`
	AllowHibernate                    bool                 `json:"allowHibernate,omitempty"`
	BlockMigrationDuringHumanTakeover bool                 `json:"blockMigrationDuringHumanTakeover,omitempty"`
	ExecutionEnvironment              ExecutionEnvironment `json:"executionEnvironment,omitempty"`
	MinimumTemplate                   string               `json:"minimumTemplate,omitempty"`
	MaximumCpuMillis                  int                  `json:"maximumCpuMillis,omitempty"`
	MaximumMemoryMib                  int                  `json:"maximumMemoryMib,omitempty"`
	MaximumCostPerHour                float64              `json:"maximumCostPerHour,omitempty"`
	ScaleUpWindowSeconds              int                  `json:"scaleUpWindowSeconds,omitempty"`
	ScaleDownWindowSeconds            int                  `json:"scaleDownWindowSeconds,omitempty"`
	AdjustmentCooldownSeconds         int                  `json:"adjustmentCooldownSeconds,omitempty"`
	ResolvedTemplate                  string               `json:"resolvedTemplate,omitempty"`
}

type ExecutionEnvironment string

const (
	ExecutionEnvironmentSYSTEMMANAGED   ExecutionEnvironment = "SYSTEM_MANAGED"
	ExecutionEnvironmentCONTAINER       ExecutionEnvironment = "CONTAINER"
	ExecutionEnvironmentENHANCEDSANDBOX ExecutionEnvironment = "ENHANCED_SANDBOX"
	ExecutionEnvironmentMICROVM         ExecutionEnvironment = "MICROVM"
	ExecutionEnvironmentNATIVEOS        ExecutionEnvironment = "NATIVE_OS"
)

type MaximumReachedPolicy string

const (
	MaximumReachedPolicyPAUSEAGENT           MaximumReachedPolicy = "PAUSE_AGENT"
	MaximumReachedPolicyWAITSAFEPOINTMIGRATE MaximumReachedPolicy = "WAIT_SAFE_POINT_MIGRATE"
	MaximumReachedPolicyHIBERNATE            MaximumReachedPolicy = "HIBERNATE"
	MaximumReachedPolicyTERMINATESTRICT      MaximumReachedPolicy = "TERMINATE_STRICT"
)

type ResourcePolicyStatus string

const (
	ResourcePolicyStatusSTABLE           ResourcePolicyStatus = "STABLE"
	ResourcePolicyStatusOBSERVING        ResourcePolicyStatus = "OBSERVING"
	ResourcePolicyStatusSCALINGUP        ResourcePolicyStatus = "SCALING_UP"
	ResourcePolicyStatusSCALINGDOWN      ResourcePolicyStatus = "SCALING_DOWN"
	ResourcePolicyStatusATMAXIMUM        ResourcePolicyStatus = "AT_MAXIMUM"
	ResourcePolicyStatusWAITINGSAFEPOINT ResourcePolicyStatus = "WAITING_SAFE_POINT"
	ResourcePolicyStatusMIGRATING        ResourcePolicyStatus = "MIGRATING"
	ResourcePolicyStatusAGENTPAUSED      ResourcePolicyStatus = "AGENT_PAUSED"
	ResourcePolicyStatusHIBERNATING      ResourcePolicyStatus = "HIBERNATING"
	ResourcePolicyStatusCRITICAL         ResourcePolicyStatus = "CRITICAL"
)

type SessionResource struct {
	SessionId       string               `json:"sessionId,omitempty"`
	Policy          ResourcePolicy       `json:"policy,omitempty"`
	Allocation      any                  `json:"allocation,omitempty"`
	Usage           any                  `json:"usage,omitempty"`
	UsageSamples    []map[string]any     `json:"usageSamples,omitempty"`
	Cost            any                  `json:"cost,omitempty"`
	Status          ResourcePolicyStatus `json:"status,omitempty"`
	StatusReason    any                  `json:"statusReason,omitempty"`
	DataFreshness   string               `json:"dataFreshness,omitempty"`
	LastEvaluatedAt any                  `json:"lastEvaluatedAt,omitempty"`
	LastAdjustedAt  any                  `json:"lastAdjustedAt,omitempty"`
}

type ResourceEventList struct {
	Items  []map[string]any `json:"items,omitempty"`
	Limit  int              `json:"limit,omitempty"`
	Offset int              `json:"offset,omitempty"`
}

type Evidence struct {
	EvidenceId          string `json:"evidenceId,omitempty"`
	EvidenceKind        string `json:"evidenceKind,omitempty"`
	TaskId              string `json:"taskId,omitempty"`
	StepId              string `json:"stepId,omitempty"`
	CommandId           string `json:"commandId,omitempty"`
	Mandatory           bool   `json:"mandatory,omitempty"`
	Result              string `json:"result,omitempty"`
	ContentSha256       any    `json:"contentSha256,omitempty"`
	ContentBytes        int64  `json:"contentBytes,omitempty"`
	CapturedAt          string `json:"capturedAt,omitempty"`
	ErrorCode           any    `json:"errorCode,omitempty"`
	RedactionState      string `json:"redactionState,omitempty"`
	RedactedRegionCount int    `json:"redactedRegionCount,omitempty"`
}

type EvidenceList struct {
	Items  []Evidence `json:"items,omitempty"`
	Limit  int        `json:"limit,omitempty"`
	Offset int        `json:"offset,omitempty"`
}

type EvidencePurpose string

const (
	EvidencePurposeINCIDENTRESPONSE   EvidencePurpose = "INCIDENT_RESPONSE"
	EvidencePurposeCHANGEVALIDATION   EvidencePurpose = "CHANGE_VALIDATION"
	EvidencePurposeSUPPORTDIAGNOSTICS EvidencePurpose = "SUPPORT_DIAGNOSTICS"
	EvidencePurposeCOMPLIANCEAUDIT    EvidencePurpose = "COMPLIANCE_AUDIT"
)

type CaptureEvidenceRequest struct {
	Purpose EvidencePurpose `json:"purpose,omitempty"`
}

type EvidenceCapture struct {
	CaptureId   string          `json:"captureId,omitempty"`
	SessionId   string          `json:"sessionId,omitempty"`
	Purpose     EvidencePurpose `json:"purpose,omitempty"`
	State       string          `json:"state,omitempty"`
	EvidenceId  any             `json:"evidenceId,omitempty"`
	ErrorCode   any             `json:"errorCode,omitempty"`
	CommandId   string          `json:"commandId,omitempty"`
	RequestId   any             `json:"requestId,omitempty"`
	CreatedAt   string          `json:"createdAt,omitempty"`
	CompletedAt any             `json:"completedAt,omitempty"`
}

type CreateEvidenceAccessGrantRequest struct {
	Purpose EvidencePurpose `json:"purpose,omitempty"`
}

type EvidenceAccessGrant struct {
	GrantId    string          `json:"grantId,omitempty"`
	SessionId  string          `json:"sessionId,omitempty"`
	EvidenceId string          `json:"evidenceId,omitempty"`
	Purpose    EvidencePurpose `json:"purpose,omitempty"`
	State      string          `json:"state,omitempty"`
	ExpiresAt  string          `json:"expiresAt,omitempty"`
	CreatedAt  string          `json:"createdAt,omitempty"`
	RedeemedAt any             `json:"redeemedAt,omitempty"`
	ErrorCode  any             `json:"errorCode,omitempty"`
	RequestId  any             `json:"requestId,omitempty"`
}

type RedeemEvidenceAccessResponse struct {
	GrantId     string `json:"grantId,omitempty"`
	EvidenceId  string `json:"evidenceId,omitempty"`
	DownloadUrl string `json:"downloadUrl,omitempty"`
	ExpiresAt   string `json:"expiresAt,omitempty"`
}

type SessionSafePoint struct {
	SessionId             string             `json:"sessionId,omitempty"`
	Safe                  bool               `json:"safe,omitempty"`
	State                 string             `json:"state,omitempty"`
	DataFreshness         string             `json:"dataFreshness,omitempty"`
	NodeId                any                `json:"nodeId,omitempty"`
	ContextEpoch          int64              `json:"contextEpoch,omitempty"`
	EvaluatedAt           string             `json:"evaluatedAt,omitempty"`
	LastNodeObservationAt any                `json:"lastNodeObservationAt,omitempty"`
	Blockers              []SafePointBlocker `json:"blockers,omitempty"`
}

type SafePointBlocker struct {
	Code       string `json:"code,omitempty"`
	Source     string `json:"source,omitempty"`
	Detail     string `json:"detail,omitempty"`
	ObservedAt any    `json:"observedAt,omitempty"`
	ExpiresAt  any    `json:"expiresAt,omitempty"`
}

type CreateSafetyLeaseRequest struct {
	SignalType string `json:"signalType,omitempty"`
	ReasonCode string `json:"reasonCode,omitempty"`
	TtlSeconds int    `json:"ttlSeconds,omitempty"`
}

type RenewSafetyLeaseRequest struct {
	TtlSeconds int `json:"ttlSeconds,omitempty"`
}

type SafetyLease struct {
	LeaseId      string `json:"leaseId,omitempty"`
	SessionId    string `json:"sessionId,omitempty"`
	ContextEpoch int64  `json:"contextEpoch,omitempty"`
	SignalType   string `json:"signalType,omitempty"`
	ReasonCode   string `json:"reasonCode,omitempty"`
	OwnerActorId string `json:"ownerActorId,omitempty"`
	State        string `json:"state,omitempty"`
	AcquiredAt   string `json:"acquiredAt,omitempty"`
	RenewedAt    string `json:"renewedAt,omitempty"`
	ExpiresAt    string `json:"expiresAt,omitempty"`
	ReleasedAt   any    `json:"releasedAt,omitempty"`
}

type SafetyLeaseList struct {
	Items []SafetyLease `json:"items,omitempty"`
	Total int           `json:"total,omitempty"`
}

type SessionMigration struct {
	MigrationId              string   `json:"migrationId,omitempty"`
	SessionId                string   `json:"sessionId,omitempty"`
	SourceNodeId             string   `json:"sourceNodeId,omitempty"`
	TargetNodeId             any      `json:"targetNodeId,omitempty"`
	SourceContextEpoch       int64    `json:"sourceContextEpoch,omitempty"`
	TargetContextEpoch       any      `json:"targetContextEpoch,omitempty"`
	CheckpointId             any      `json:"checkpointId,omitempty"`
	HibernateOperationId     any      `json:"hibernateOperationId,omitempty"`
	RestoreOperationId       any      `json:"restoreOperationId,omitempty"`
	TargetCleanupOperationId any      `json:"targetCleanupOperationId,omitempty"`
	TargetAttempt            int      `json:"targetAttempt,omitempty"`
	MaximumTargetAttempts    int      `json:"maximumTargetAttempts,omitempty"`
	FailedTargetNodeIds      []string `json:"failedTargetNodeIds,omitempty"`
	LastTargetFailureReason  any      `json:"lastTargetFailureReason,omitempty"`
	ResyncRequestId          any      `json:"resyncRequestId,omitempty"`
	Phase                    string   `json:"phase,omitempty"`
	RecoveryResult           any      `json:"recoveryResult,omitempty"`
	FailureReason            any      `json:"failureReason,omitempty"`
	AutoRecoveryAttempts     int      `json:"autoRecoveryAttempts,omitempty"`
	AutoRecoveryMaximum      int      `json:"autoRecoveryMaximum,omitempty"`
	LatestRecoveryAction     any      `json:"latestRecoveryAction,omitempty"`
	CreatedAt                string   `json:"createdAt,omitempty"`
	UpdatedAt                string   `json:"updatedAt,omitempty"`
	CompletedAt              any      `json:"completedAt,omitempty"`
}

type BusinessRecoveryAction struct {
	ActionId              string `json:"actionId,omitempty"`
	MigrationId           string `json:"migrationId,omitempty"`
	AttemptNumber         int    `json:"attemptNumber,omitempty"`
	Action                string `json:"action,omitempty"`
	TargetUrl             any    `json:"targetUrl,omitempty"`
	TargetExtensionId     any    `json:"targetExtensionId,omitempty"`
	BaseStateVersion      int64  `json:"baseStateVersion,omitempty"`
	ResultingStateVersion any    `json:"resultingStateVersion,omitempty"`
	State                 string `json:"state,omitempty"`
	ErrorCode             any    `json:"errorCode,omitempty"`
	CreatedAt             string `json:"createdAt,omitempty"`
	CompletedAt           any    `json:"completedAt,omitempty"`
}

type ResourcePolicyOperation struct {
	OperationId    string         `json:"operationId,omitempty"`
	State          string         `json:"state,omitempty"`
	ResourcePolicy ResourcePolicy `json:"resourcePolicy,omitempty"`
}

type SessionContext struct {
	SessionId          string           `json:"sessionId,omitempty"`
	TenantId           string           `json:"tenantId,omitempty"`
	ProfileId          string           `json:"profileId,omitempty"`
	NodeId             any              `json:"nodeId,omitempty"`
	RuntimeBuildId     any              `json:"runtimeBuildId,omitempty"`
	IsolationProfileId any              `json:"isolationProfileId,omitempty"`
	ProxyBindingId     any              `json:"proxyBindingId,omitempty"`
	CoordinatorTerm    int64            `json:"coordinatorTerm,omitempty"`
	ContextEpoch       int64            `json:"contextEpoch,omitempty"`
	BrowserGeneration  int64            `json:"browserGeneration,omitempty"`
	NetworkRevision    int64            `json:"networkRevision,omitempty"`
	ResourceTemplate   ResourceTemplate `json:"resourceTemplate,omitempty"`
	State              SessionState     `json:"state,omitempty"`
	PolicyHash         string           `json:"policyHash,omitempty"`
	CreatedAt          string           `json:"createdAt,omitempty"`
	UpdatedAt          string           `json:"updatedAt,omitempty"`
}

type SessionView struct {
	SessionId             string                `json:"sessionId,omitempty"`
	DisplayName           string                `json:"displayName,omitempty"`
	TenantId              string                `json:"tenantId,omitempty"`
	ProfileId             string                `json:"profileId,omitempty"`
	GroupId               any                   `json:"groupId,omitempty"`
	Tags                  []WorkspaceTagSummary `json:"tags,omitempty"`
	HumanTakeoverEnabled  bool                  `json:"humanTakeoverEnabled,omitempty"`
	AgentPolicy           AgentPolicy           `json:"agentPolicy,omitempty"`
	ExtensionIds          []string              `json:"extensionIds,omitempty"`
	Region                string                `json:"region,omitempty"`
	ResourceTemplate      ResourceTemplate      `json:"resourceTemplate,omitempty"`
	State                 SessionState          `json:"state,omitempty"`
	NodeId                any                   `json:"nodeId,omitempty"`
	RuntimeBuildId        any                   `json:"runtimeBuildId,omitempty"`
	ProxyBindingId        any                   `json:"proxyBindingId,omitempty"`
	ProxyBindingProfileId any                   `json:"proxyBindingProfileId,omitempty"`
	ProxyRoutingDecision  any                   `json:"proxyRoutingDecision,omitempty"`
	ContextEpoch          int64                 `json:"contextEpoch,omitempty"`
	BrowserGeneration     int64                 `json:"browserGeneration,omitempty"`
	CurrentOperation      any                   `json:"currentOperation,omitempty"`
	CreatedAt             string                `json:"createdAt,omitempty"`
	UpdatedAt             string                `json:"updatedAt,omitempty"`
}

type EnvironmentSavedViewScope string

const (
	EnvironmentSavedViewScopePERSONAL  EnvironmentSavedViewScope = "PERSONAL"
	EnvironmentSavedViewScopeWORKSPACE EnvironmentSavedViewScope = "WORKSPACE"
)

type EnvironmentPrimaryView string

const (
	EnvironmentPrimaryViewALL      EnvironmentPrimaryView = "ALL"
	EnvironmentPrimaryViewRUNNING  EnvironmentPrimaryView = "RUNNING"
	EnvironmentPrimaryViewSTOPPED  EnvironmentPrimaryView = "STOPPED"
	EnvironmentPrimaryViewABNORMAL EnvironmentPrimaryView = "ABNORMAL"
)

type EnvironmentSavedViewTagMatch string

const (
	EnvironmentSavedViewTagMatchANY EnvironmentSavedViewTagMatch = "ANY"
	EnvironmentSavedViewTagMatchALL EnvironmentSavedViewTagMatch = "ALL"
)

type CreateEnvironmentSavedViewRequest struct {
	Name                string                    `json:"name,omitempty"`
	Scope               EnvironmentSavedViewScope `json:"scope,omitempty"`
	PrimaryView         EnvironmentPrimaryView    `json:"primaryView,omitempty"`
	SessionState        any                       `json:"sessionState,omitempty"`
	SearchQuery         string                    `json:"searchQuery,omitempty"`
	GroupId             any                       `json:"groupId,omitempty"`
	TagIds              []string                  `json:"tagIds,omitempty"`
	TagMatch            any                       `json:"tagMatch,omitempty"`
	ShowRuntimeColumn   bool                      `json:"showRuntimeColumn,omitempty"`
	ShowContextColumn   bool                      `json:"showContextColumn,omitempty"`
	ShowOperationColumn bool                      `json:"showOperationColumn,omitempty"`
}

type UpdateEnvironmentSavedViewRequest struct {
	ExpectedVersion     int64                  `json:"expectedVersion,omitempty"`
	Name                string                 `json:"name,omitempty"`
	PrimaryView         EnvironmentPrimaryView `json:"primaryView,omitempty"`
	SessionState        any                    `json:"sessionState,omitempty"`
	SearchQuery         string                 `json:"searchQuery,omitempty"`
	GroupId             any                    `json:"groupId,omitempty"`
	TagIds              []string               `json:"tagIds,omitempty"`
	TagMatch            any                    `json:"tagMatch,omitempty"`
	ShowRuntimeColumn   bool                   `json:"showRuntimeColumn,omitempty"`
	ShowContextColumn   bool                   `json:"showContextColumn,omitempty"`
	ShowOperationColumn bool                   `json:"showOperationColumn,omitempty"`
}

type EnvironmentSavedView struct {
	SavedViewId         string                       `json:"savedViewId,omitempty"`
	Name                string                       `json:"name,omitempty"`
	Scope               EnvironmentSavedViewScope    `json:"scope,omitempty"`
	OwnerActorId        string                       `json:"ownerActorId,omitempty"`
	PrimaryView         EnvironmentPrimaryView       `json:"primaryView,omitempty"`
	SessionState        any                          `json:"sessionState,omitempty"`
	SearchQuery         string                       `json:"searchQuery,omitempty"`
	GroupId             any                          `json:"groupId,omitempty"`
	TagIds              []string                     `json:"tagIds,omitempty"`
	TagMatch            EnvironmentSavedViewTagMatch `json:"tagMatch,omitempty"`
	ShowRuntimeColumn   bool                         `json:"showRuntimeColumn,omitempty"`
	ShowContextColumn   bool                         `json:"showContextColumn,omitempty"`
	ShowOperationColumn bool                         `json:"showOperationColumn,omitempty"`
	CreatedAt           string                       `json:"createdAt,omitempty"`
	UpdatedAt           string                       `json:"updatedAt,omitempty"`
	Version             int64                        `json:"version,omitempty"`
}

type EnvironmentSavedViewListResponse struct {
	Items []EnvironmentSavedView `json:"items,omitempty"`
	Total int                    `json:"total,omitempty"`
}

type EnvironmentImportState string

const (
	EnvironmentImportStateVALIDATED EnvironmentImportState = "VALIDATED"
	EnvironmentImportStateINVALID   EnvironmentImportState = "INVALID"
	EnvironmentImportStateEXECUTING EnvironmentImportState = "EXECUTING"
	EnvironmentImportStateCOMMITTED EnvironmentImportState = "COMMITTED"
)

type EnvironmentImportValidationState string

const (
	EnvironmentImportValidationStateREADY   EnvironmentImportValidationState = "READY"
	EnvironmentImportValidationStateINVALID EnvironmentImportValidationState = "INVALID"
)

type EnvironmentImportExecutionState string

const (
	EnvironmentImportExecutionStatePENDING   EnvironmentImportExecutionState = "PENDING"
	EnvironmentImportExecutionStateSUCCEEDED EnvironmentImportExecutionState = "SUCCEEDED"
)

type EnvironmentImportSpec struct {
	DisplayName           string `json:"displayName,omitempty"`
	Description           any    `json:"description,omitempty"`
	ProfileId             string `json:"profileId,omitempty"`
	RuntimeBuildId        any    `json:"runtimeBuildId,omitempty"`
	ApplicationId         any    `json:"applicationId,omitempty"`
	GroupId               any    `json:"groupId,omitempty"`
	TagIds                any    `json:"tagIds,omitempty"`
	Region                any    `json:"region,omitempty"`
	ResourcePolicy        any    `json:"resourcePolicy,omitempty"`
	RequestedTabs         int    `json:"requestedTabs,omitempty"`
	AgentActionsPerMinute int    `json:"agentActionsPerMinute,omitempty"`
	RemoteDesktop         bool   `json:"remoteDesktop,omitempty"`
	HumanTakeoverEnabled  any    `json:"humanTakeoverEnabled,omitempty"`
	AgentPolicy           any    `json:"agentPolicy,omitempty"`
	Web3Workload          bool   `json:"web3Workload,omitempty"`
	MediaWorkload         bool   `json:"mediaWorkload,omitempty"`
	RequestedMediaStreams int    `json:"requestedMediaStreams,omitempty"`
	MediaBitrateKbps      int    `json:"mediaBitrateKbps,omitempty"`
	VideoRecording        bool   `json:"videoRecording,omitempty"`
	ExtensionIds          any    `json:"extensionIds,omitempty"`
}

type PreviewEnvironmentImportRequest struct {
	SchemaVersion int                     `json:"schemaVersion,omitempty"`
	Name          string                  `json:"name,omitempty"`
	Environments  []EnvironmentImportSpec `json:"environments,omitempty"`
}

type CommitEnvironmentImportRequest struct {
	ExpectedVersion int64 `json:"expectedVersion,omitempty"`
}

type EnvironmentImportItem struct {
	ItemId           string                           `json:"itemId,omitempty"`
	ItemIndex        int                              `json:"itemIndex,omitempty"`
	Specification    EnvironmentImportSpec            `json:"specification,omitempty"`
	ValidationState  EnvironmentImportValidationState `json:"validationState,omitempty"`
	ValidationErrors []string                         `json:"validationErrors,omitempty"`
	ExecutionState   EnvironmentImportExecutionState  `json:"executionState,omitempty"`
	SessionId        any                              `json:"sessionId,omitempty"`
	OperationId      any                              `json:"operationId,omitempty"`
	RequestId        any                              `json:"requestId,omitempty"`
	UpdatedAt        string                           `json:"updatedAt,omitempty"`
}

type EnvironmentImport struct {
	ImportId       string                  `json:"importId,omitempty"`
	Name           string                  `json:"name,omitempty"`
	SchemaVersion  int                     `json:"schemaVersion,omitempty"`
	ManifestHash   string                  `json:"manifestHash,omitempty"`
	State          EnvironmentImportState  `json:"state,omitempty"`
	TotalCount     int                     `json:"totalCount,omitempty"`
	ReadyCount     int                     `json:"readyCount,omitempty"`
	SucceededCount int                     `json:"succeededCount,omitempty"`
	Items          []EnvironmentImportItem `json:"items,omitempty"`
	CreatedAt      string                  `json:"createdAt,omitempty"`
	UpdatedAt      string                  `json:"updatedAt,omitempty"`
	CommittedAt    any                     `json:"committedAt,omitempty"`
	Version        int64                   `json:"version,omitempty"`
}

type EnvironmentImportListItem struct {
	ImportId       string                 `json:"importId,omitempty"`
	Name           string                 `json:"name,omitempty"`
	State          EnvironmentImportState `json:"state,omitempty"`
	TotalCount     int                    `json:"totalCount,omitempty"`
	ReadyCount     int                    `json:"readyCount,omitempty"`
	SucceededCount int                    `json:"succeededCount,omitempty"`
	CreatedAt      string                 `json:"createdAt,omitempty"`
	UpdatedAt      string                 `json:"updatedAt,omitempty"`
	Version        int64                  `json:"version,omitempty"`
}

type EnvironmentImportListResponse struct {
	Items []EnvironmentImportListItem `json:"items,omitempty"`
	Total int                         `json:"total,omitempty"`
}

type WorkspaceGroupRequest struct {
	Name                    string               `json:"name,omitempty"`
	Description             any                  `json:"description,omitempty"`
	Color                   string               `json:"color,omitempty"`
	DefaultOnMaximumReached MaximumReachedPolicy `json:"defaultOnMaximumReached,omitempty"`
	DefaultAllowMigration   bool                 `json:"defaultAllowMigration,omitempty"`
	DefaultAllowHibernate   bool                 `json:"defaultAllowHibernate,omitempty"`
}

type WorkspaceGroupSession struct {
	SessionId   string       `json:"sessionId,omitempty"`
	DisplayName string       `json:"displayName,omitempty"`
	State       SessionState `json:"state,omitempty"`
	Region      string       `json:"region,omitempty"`
	UpdatedAt   string       `json:"updatedAt,omitempty"`
}

type WorkspaceGroup struct {
	GroupId                 string                  `json:"groupId,omitempty"`
	Name                    string                  `json:"name,omitempty"`
	Description             any                     `json:"description,omitempty"`
	Color                   string                  `json:"color,omitempty"`
	DefaultOnMaximumReached MaximumReachedPolicy    `json:"defaultOnMaximumReached,omitempty"`
	DefaultAllowMigration   bool                    `json:"defaultAllowMigration,omitempty"`
	DefaultAllowHibernate   bool                    `json:"defaultAllowHibernate,omitempty"`
	Sessions                []WorkspaceGroupSession `json:"sessions,omitempty"`
	SessionCount            int                     `json:"sessionCount,omitempty"`
	CreatedBy               string                  `json:"createdBy,omitempty"`
	CreatedAt               string                  `json:"createdAt,omitempty"`
	UpdatedAt               string                  `json:"updatedAt,omitempty"`
}

type WorkspaceGroupListResponse struct {
	Items              []WorkspaceGroup        `json:"items,omitempty"`
	UnassignedSessions []WorkspaceGroupSession `json:"unassignedSessions,omitempty"`
	Total              int                     `json:"total,omitempty"`
}

type WorkspaceTagRequest struct {
	Name        string `json:"name,omitempty"`
	Description any    `json:"description,omitempty"`
	Color       string `json:"color,omitempty"`
}

type WorkspaceTagSummary struct {
	TagId string `json:"tagId,omitempty"`
	Name  string `json:"name,omitempty"`
	Color string `json:"color,omitempty"`
}

type WorkspaceTagSession struct {
	SessionId   string       `json:"sessionId,omitempty"`
	DisplayName string       `json:"displayName,omitempty"`
	State       SessionState `json:"state,omitempty"`
	Region      string       `json:"region,omitempty"`
	UpdatedAt   string       `json:"updatedAt,omitempty"`
}

type WorkspaceTag struct {
	TagId        string                `json:"tagId,omitempty"`
	Name         string                `json:"name,omitempty"`
	Description  any                   `json:"description,omitempty"`
	Color        string                `json:"color,omitempty"`
	Sessions     []WorkspaceTagSession `json:"sessions,omitempty"`
	SessionCount int                   `json:"sessionCount,omitempty"`
	CreatedBy    string                `json:"createdBy,omitempty"`
	CreatedAt    string                `json:"createdAt,omitempty"`
	UpdatedAt    string                `json:"updatedAt,omitempty"`
}

type WorkspaceTagListResponse struct {
	Items    []WorkspaceTag        `json:"items,omitempty"`
	Sessions []WorkspaceTagSession `json:"sessions,omitempty"`
	Total    int                   `json:"total,omitempty"`
}

type WorkspaceBatchAction string

const (
	WorkspaceBatchActionSTART      WorkspaceBatchAction = "START"
	WorkspaceBatchActionPAUSEAGENT WorkspaceBatchAction = "PAUSE_AGENT"
	WorkspaceBatchActionMIGRATE    WorkspaceBatchAction = "MIGRATE"
	WorkspaceBatchActionHIBERNATE  WorkspaceBatchAction = "HIBERNATE"
)

type WorkspaceBatchState string

const (
	WorkspaceBatchStateACCEPTED       WorkspaceBatchState = "ACCEPTED"
	WorkspaceBatchStateEXECUTING      WorkspaceBatchState = "EXECUTING"
	WorkspaceBatchStateCANCELLING     WorkspaceBatchState = "CANCELLING"
	WorkspaceBatchStateSUCCEEDED      WorkspaceBatchState = "SUCCEEDED"
	WorkspaceBatchStatePARTIALSUCCESS WorkspaceBatchState = "PARTIAL_SUCCESS"
	WorkspaceBatchStateFAILED         WorkspaceBatchState = "FAILED"
	WorkspaceBatchStateCANCELLED      WorkspaceBatchState = "CANCELLED"
)

type WorkspaceBatchItemState string

const (
	WorkspaceBatchItemStateACCEPTED  WorkspaceBatchItemState = "ACCEPTED"
	WorkspaceBatchItemStateEXECUTING WorkspaceBatchItemState = "EXECUTING"
	WorkspaceBatchItemStateSUCCEEDED WorkspaceBatchItemState = "SUCCEEDED"
	WorkspaceBatchItemStateFAILED    WorkspaceBatchItemState = "FAILED"
	WorkspaceBatchItemStateCANCELLED WorkspaceBatchItemState = "CANCELLED"
)

type WorkspaceBatchSelector struct {
	GroupId    any      `json:"groupId,omitempty"`
	TagIds     []string `json:"tagIds,omitempty"`
	TagMatch   string   `json:"tagMatch,omitempty"`
	SessionIds []string `json:"sessionIds,omitempty"`
}

type CreateWorkspaceBatchOperationRequest struct {
	Action    WorkspaceBatchAction   `json:"action,omitempty"`
	Selector  WorkspaceBatchSelector `json:"selector,omitempty"`
	Reason    any                    `json:"reason,omitempty"`
	Confirmed bool                   `json:"confirmed,omitempty"`
}

type CancelWorkspaceBatchOperationRequest struct {
	Reason string `json:"reason,omitempty"`
}

type WorkspaceBatchOperationItem struct {
	BatchItemId      string                  `json:"batchItemId,omitempty"`
	SessionId        string                  `json:"sessionId,omitempty"`
	Ordinal          int                     `json:"ordinal,omitempty"`
	CommandId        string                  `json:"commandId,omitempty"`
	State            WorkspaceBatchItemState `json:"state,omitempty"`
	ChildOperationId any                     `json:"childOperationId,omitempty"`
	FailureCode      any                     `json:"failureCode,omitempty"`
	CreatedAt        string                  `json:"createdAt,omitempty"`
	StartedAt        any                     `json:"startedAt,omitempty"`
	CompletedAt      any                     `json:"completedAt,omitempty"`
}

type WorkspaceBatchOperation struct {
	BatchOperationId      string                        `json:"batchOperationId,omitempty"`
	Action                WorkspaceBatchAction          `json:"action,omitempty"`
	State                 WorkspaceBatchState           `json:"state,omitempty"`
	Selector              WorkspaceBatchSelector        `json:"selector,omitempty"`
	Reason                any                           `json:"reason,omitempty"`
	Total                 int                           `json:"total,omitempty"`
	Accepted              int                           `json:"accepted,omitempty"`
	Executing             int                           `json:"executing,omitempty"`
	Succeeded             int                           `json:"succeeded,omitempty"`
	Failed                int                           `json:"failed,omitempty"`
	Cancelled             int                           `json:"cancelled,omitempty"`
	CancellationRequested bool                          `json:"cancellationRequested,omitempty"`
	Items                 []WorkspaceBatchOperationItem `json:"items,omitempty"`
	ActorId               string                        `json:"actorId,omitempty"`
	CreatedAt             string                        `json:"createdAt,omitempty"`
	UpdatedAt             string                        `json:"updatedAt,omitempty"`
}

type WorkspaceBatchOperationListResponse struct {
	Items []WorkspaceBatchOperation `json:"items,omitempty"`
	Total int                       `json:"total,omitempty"`
}

type WorkspaceMetadataBatchAction string

const (
	WorkspaceMetadataBatchActionASSIGNGROUP WorkspaceMetadataBatchAction = "ASSIGN_GROUP"
	WorkspaceMetadataBatchActionREMOVEGROUP WorkspaceMetadataBatchAction = "REMOVE_GROUP"
	WorkspaceMetadataBatchActionASSIGNTAGS  WorkspaceMetadataBatchAction = "ASSIGN_TAGS"
	WorkspaceMetadataBatchActionREMOVETAGS  WorkspaceMetadataBatchAction = "REMOVE_TAGS"
)

type WorkspaceMetadataBatchSelector struct {
	GroupId    any      `json:"groupId,omitempty"`
	TagIds     []string `json:"tagIds,omitempty"`
	TagMatch   string   `json:"tagMatch,omitempty"`
	SessionIds []string `json:"sessionIds,omitempty"`
}

type WorkspaceMetadataBatchTarget struct {
	GroupId any      `json:"groupId,omitempty"`
	TagIds  []string `json:"tagIds,omitempty"`
}

type CreateWorkspaceMetadataBatchOperationRequest struct {
	Action    WorkspaceMetadataBatchAction   `json:"action,omitempty"`
	Selector  WorkspaceMetadataBatchSelector `json:"selector,omitempty"`
	Target    WorkspaceMetadataBatchTarget   `json:"target,omitempty"`
	Reason    string                         `json:"reason,omitempty"`
	Confirmed bool                           `json:"confirmed,omitempty"`
}

type WorkspaceMetadataBatchOperationItem struct {
	BatchItemId string                  `json:"batchItemId,omitempty"`
	SessionId   string                  `json:"sessionId,omitempty"`
	Ordinal     int                     `json:"ordinal,omitempty"`
	State       WorkspaceBatchItemState `json:"state,omitempty"`
	FailureCode any                     `json:"failureCode,omitempty"`
	Attempt     int                     `json:"attempt,omitempty"`
	CreatedAt   string                  `json:"createdAt,omitempty"`
	StartedAt   any                     `json:"startedAt,omitempty"`
	CompletedAt any                     `json:"completedAt,omitempty"`
}

type WorkspaceMetadataBatchOperation struct {
	BatchOperationId      string                                `json:"batchOperationId,omitempty"`
	Action                WorkspaceMetadataBatchAction          `json:"action,omitempty"`
	State                 WorkspaceBatchState                   `json:"state,omitempty"`
	Selector              WorkspaceMetadataBatchSelector        `json:"selector,omitempty"`
	Target                WorkspaceMetadataBatchTarget          `json:"target,omitempty"`
	Reason                string                                `json:"reason,omitempty"`
	Total                 int                                   `json:"total,omitempty"`
	Accepted              int                                   `json:"accepted,omitempty"`
	Executing             int                                   `json:"executing,omitempty"`
	Succeeded             int                                   `json:"succeeded,omitempty"`
	Failed                int                                   `json:"failed,omitempty"`
	Cancelled             int                                   `json:"cancelled,omitempty"`
	CancellationRequested bool                                  `json:"cancellationRequested,omitempty"`
	Items                 []WorkspaceMetadataBatchOperationItem `json:"items,omitempty"`
	ActorId               string                                `json:"actorId,omitempty"`
	CreatedAt             string                                `json:"createdAt,omitempty"`
	UpdatedAt             string                                `json:"updatedAt,omitempty"`
}

type WorkspaceMetadataBatchOperationListResponse struct {
	Items []WorkspaceMetadataBatchOperation `json:"items,omitempty"`
	Total int                               `json:"total,omitempty"`
}

type WorkspaceSettingsRequest struct {
	WorkspaceName               string `json:"workspaceName,omitempty"`
	DefaultRuntimeBuildId       string `json:"defaultRuntimeBuildId,omitempty"`
	DefaultRegion               string `json:"defaultRegion,omitempty"`
	DefaultHumanTakeoverEnabled bool   `json:"defaultHumanTakeoverEnabled,omitempty"`
}

type WorkspaceSettings struct {
	WorkspaceName               string `json:"workspaceName,omitempty"`
	DefaultRuntimeBuildId       string `json:"defaultRuntimeBuildId,omitempty"`
	DefaultRegion               string `json:"defaultRegion,omitempty"`
	DefaultHumanTakeoverEnabled bool   `json:"defaultHumanTakeoverEnabled,omitempty"`
	ResourcePolicyMode          string `json:"resourcePolicyMode,omitempty"`
	OnMaximumReached            string `json:"onMaximumReached,omitempty"`
	Source                      string `json:"source,omitempty"`
	UpdatedBy                   any    `json:"updatedBy,omitempty"`
	UpdatedAt                   any    `json:"updatedAt,omitempty"`
	Version                     int64  `json:"version,omitempty"`
}

type SessionListResponse struct {
	Items  []SessionView `json:"items,omitempty"`
	Total  int           `json:"total,omitempty"`
	Limit  int           `json:"limit,omitempty"`
	Offset int           `json:"offset,omitempty"`
}

type OperationResponse struct {
	OperationId string `json:"operationId,omitempty"`
	State       string `json:"state,omitempty"`
}

type OperationView struct {
	OperationId         string   `json:"operationId,omitempty"`
	OwnerType           string   `json:"ownerType,omitempty"`
	ActorId             any      `json:"actorId,omitempty"`
	Mode                string   `json:"mode,omitempty"`
	Priority            int      `json:"priority,omitempty"`
	CoordinatorTerm     int64    `json:"coordinatorTerm,omitempty"`
	ContextEpoch        int64    `json:"contextEpoch,omitempty"`
	OperationEpoch      int64    `json:"operationEpoch,omitempty"`
	WorkflowId          any      `json:"workflowId,omitempty"`
	Cancellable         bool     `json:"cancellable,omitempty"`
	Preemptible         bool     `json:"preemptible,omitempty"`
	Phase               string   `json:"phase,omitempty"`
	State               string   `json:"state,omitempty"`
	AllowedCapabilities []string `json:"allowedCapabilities,omitempty"`
	Deadline            string   `json:"deadline,omitempty"`
}

type RemoteDesktopConnection struct {
	WebSocketPath  string `json:"webSocketPath,omitempty"`
	ExpiresAt      string `json:"expiresAt,omitempty"`
	Protocol       string `json:"protocol,omitempty"`
	OperationEpoch int64  `json:"operationEpoch,omitempty"`
	ViewOnly       bool   `json:"viewOnly,omitempty"`
}

type AuditEvent struct {
	EventId           string         `json:"eventId,omitempty"`
	SequenceNo        int64          `json:"sequenceNo,omitempty"`
	SessionId         any            `json:"sessionId,omitempty"`
	EventType         string         `json:"eventType,omitempty"`
	ActorType         string         `json:"actorType,omitempty"`
	ActorId           any            `json:"actorId,omitempty"`
	ResourceType      any            `json:"resourceType,omitempty"`
	ResourceId        any            `json:"resourceId,omitempty"`
	Action            string         `json:"action,omitempty"`
	Result            string         `json:"result,omitempty"`
	Details           map[string]any `json:"details,omitempty"`
	PreviousEventHash any            `json:"previousEventHash,omitempty"`
	EventHash         string         `json:"eventHash,omitempty"`
	RequestId         any            `json:"requestId,omitempty"`
	RetentionUntil    string         `json:"retentionUntil,omitempty"`
	LegalHold         bool           `json:"legalHold,omitempty"`
	CreatedAt         string         `json:"createdAt,omitempty"`
}

type AuditEventListResponse struct {
	Items      []AuditEvent `json:"items,omitempty"`
	Total      int64        `json:"total,omitempty"`
	ChainValid bool         `json:"chainValid,omitempty"`
	HeadHash   any          `json:"headHash,omitempty"`
}

type RuntimeBuild struct {
	BuildId           string `json:"buildId,omitempty"`
	Engine            string `json:"engine,omitempty"`
	Version           string `json:"version,omitempty"`
	Platform          string `json:"platform,omitempty"`
	SecurityTier      string `json:"securityTier,omitempty"`
	RegressionStatus  string `json:"regressionStatus,omitempty"`
	ReleaseChannel    string `json:"releaseChannel,omitempty"`
	SignatureVerified bool   `json:"signatureVerified,omitempty"`
	Signature         any    `json:"signature,omitempty"`
	ArtifactDigest    any    `json:"artifactDigest,omitempty"`
	SigningKeyId      any    `json:"signingKeyId,omitempty"`
	SbomUrl           any    `json:"sbomUrl,omitempty"`
	ValidatedAt       any    `json:"validatedAt,omitempty"`
	ReleasedAt        any    `json:"releasedAt,omitempty"`
	DisabledAt        any    `json:"disabledAt,omitempty"`
	DisabledBy        any    `json:"disabledBy,omitempty"`
	CreatedAt         string `json:"createdAt,omitempty"`
}

type RuntimeBuildListResponse struct {
	Items []RuntimeBuild `json:"items,omitempty"`
	Total int            `json:"total,omitempty"`
}

type CreateRuntimeReleaseRequest struct {
	TargetChannel string `json:"targetChannel,omitempty"`
	Reason        string `json:"reason,omitempty"`
}

type CreateRuntimeDisableRequest struct {
	Reason string `json:"reason,omitempty"`
}

type RuntimeReleaseRequest struct {
	ReleaseId     string `json:"releaseId,omitempty"`
	BuildId       string `json:"buildId,omitempty"`
	TargetChannel string `json:"targetChannel,omitempty"`
	Reason        string `json:"reason,omitempty"`
	State         string `json:"state,omitempty"`
	RequestedBy   string `json:"requestedBy,omitempty"`
	ApprovedBy    any    `json:"approvedBy,omitempty"`
	RejectedBy    any    `json:"rejectedBy,omitempty"`
	RequestedAt   string `json:"requestedAt,omitempty"`
	DecidedAt     any    `json:"decidedAt,omitempty"`
	EvidenceHash  any    `json:"evidenceHash,omitempty"`
}

type RuntimeReleaseRequestListResponse struct {
	Items []RuntimeReleaseRequest `json:"items,omitempty"`
	Total int                     `json:"total,omitempty"`
}

type CreateKeyRotationRequest struct {
	KeyScope        string `json:"keyScope,omitempty"`
	OldKeyId        string `json:"oldKeyId,omitempty"`
	NewKeyId        string `json:"newKeyId,omitempty"`
	RotationTrigger string `json:"rotationTrigger,omitempty"`
	Reason          string `json:"reason,omitempty"`
	OverlapMinutes  int    `json:"overlapMinutes,omitempty"`
}

type CompleteKeyRotationRequest struct {
	NewKeyWriteVerified   bool   `json:"newKeyWriteVerified,omitempty"`
	OldKeyReadVerified    bool   `json:"oldKeyReadVerified,omitempty"`
	PlaintextRejected     bool   `json:"plaintextRejected,omitempty"`
	AffectedWorkloads     int    `json:"affectedWorkloads,omitempty"`
	VerificationReference string `json:"verificationReference,omitempty"`
}

type KeyRotationRequest struct {
	RotationId              string `json:"rotationId,omitempty"`
	KeyScope                string `json:"keyScope,omitempty"`
	OldKeyId                string `json:"oldKeyId,omitempty"`
	NewKeyId                string `json:"newKeyId,omitempty"`
	RotationTrigger         string `json:"rotationTrigger,omitempty"`
	Reason                  string `json:"reason,omitempty"`
	RequestedOverlapMinutes int    `json:"requestedOverlapMinutes,omitempty"`
	State                   string `json:"state,omitempty"`
	RequestedBy             string `json:"requestedBy,omitempty"`
	ApprovedBy              any    `json:"approvedBy,omitempty"`
	CompletedBy             any    `json:"completedBy,omitempty"`
	RevokedBy               any    `json:"revokedBy,omitempty"`
	RequestedAt             string `json:"requestedAt,omitempty"`
	ApprovedAt              any    `json:"approvedAt,omitempty"`
	StartedAt               any    `json:"startedAt,omitempty"`
	CompletedAt             any    `json:"completedAt,omitempty"`
	RevokedAt               any    `json:"revokedAt,omitempty"`
	OverlapUntil            any    `json:"overlapUntil,omitempty"`
	ProgressPercent         int    `json:"progressPercent,omitempty"`
	NewKeyWriteVerified     any    `json:"newKeyWriteVerified,omitempty"`
	OldKeyReadVerified      any    `json:"oldKeyReadVerified,omitempty"`
	PlaintextRejected       any    `json:"plaintextRejected,omitempty"`
	AffectedWorkloads       any    `json:"affectedWorkloads,omitempty"`
	VerificationReference   any    `json:"verificationReference,omitempty"`
	ApprovalEvidenceHash    any    `json:"approvalEvidenceHash,omitempty"`
	CompletionEvidenceHash  any    `json:"completionEvidenceHash,omitempty"`
}

type KeyRotationRequestListResponse struct {
	Items []KeyRotationRequest `json:"items,omitempty"`
	Total int                  `json:"total,omitempty"`
}

type CreateBreakGlassRequest struct {
	TicketId        string `json:"ticketId,omitempty"`
	Reason          string `json:"reason,omitempty"`
	ResourceType    string `json:"resourceType,omitempty"`
	ResourceId      string `json:"resourceId,omitempty"`
	RequestedScope  string `json:"requestedScope,omitempty"`
	DurationMinutes int    `json:"durationMinutes,omitempty"`
}

type BreakGlassRequest struct {
	RequestId      string `json:"requestId,omitempty"`
	TicketId       string `json:"ticketId,omitempty"`
	Reason         string `json:"reason,omitempty"`
	ResourceType   string `json:"resourceType,omitempty"`
	ResourceId     string `json:"resourceId,omitempty"`
	RequestedScope string `json:"requestedScope,omitempty"`
	State          string `json:"state,omitempty"`
	RequestedBy    string `json:"requestedBy,omitempty"`
	ApprovedBy     any    `json:"approvedBy,omitempty"`
	RejectedBy     any    `json:"rejectedBy,omitempty"`
	RevokedBy      any    `json:"revokedBy,omitempty"`
	EvidenceHash   any    `json:"evidenceHash,omitempty"`
	RequestedAt    string `json:"requestedAt,omitempty"`
	ApprovedAt     any    `json:"approvedAt,omitempty"`
	RejectedAt     any    `json:"rejectedAt,omitempty"`
	RevokedAt      any    `json:"revokedAt,omitempty"`
	ExpiresAt      string `json:"expiresAt,omitempty"`
	ReviewedAt     any    `json:"reviewedAt,omitempty"`
}

type BreakGlassRequestListResponse struct {
	Items []BreakGlassRequest `json:"items,omitempty"`
	Total int                 `json:"total,omitempty"`
}

type SecureDebugSession struct {
	DebugSessionId      string `json:"debugSessionId,omitempty"`
	BreakGlassRequestId string `json:"breakGlassRequestId,omitempty"`
	ResourceType        string `json:"resourceType,omitempty"`
	ResourceId          string `json:"resourceId,omitempty"`
	OperatorId          string `json:"operatorId,omitempty"`
	State               string `json:"state,omitempty"`
	StartedAt           string `json:"startedAt,omitempty"`
	ExpiresAt           string `json:"expiresAt,omitempty"`
	EndedAt             any    `json:"endedAt,omitempty"`
	EndReason           any    `json:"endReason,omitempty"`
	AccessCount         int    `json:"accessCount,omitempty"`
	LastAccessAt        any    `json:"lastAccessAt,omitempty"`
	EvidenceHeadHash    any    `json:"evidenceHeadHash,omitempty"`
}

type SecureDebugSessionListResponse struct {
	Items []SecureDebugSession `json:"items,omitempty"`
	Total int                  `json:"total,omitempty"`
}

type SecureDebugSnapshot struct {
	DebugSessionId         string `json:"debugSessionId,omitempty"`
	SessionId              string `json:"sessionId,omitempty"`
	SessionState           string `json:"sessionState,omitempty"`
	RuntimeBuildId         any    `json:"runtimeBuildId,omitempty"`
	ContextEpoch           int    `json:"contextEpoch,omitempty"`
	BrowserGeneration      int    `json:"browserGeneration,omitempty"`
	NetworkRevision        int    `json:"networkRevision,omitempty"`
	UrlOrigin              any    `json:"urlOrigin,omitempty"`
	StateVersion           int    `json:"stateVersion,omitempty"`
	TargetRevision         int    `json:"targetRevision,omitempty"`
	StateQuality           string `json:"stateQuality,omitempty"`
	StateHash              any    `json:"stateHash,omitempty"`
	InteractiveTargetCount int    `json:"interactiveTargetCount,omitempty"`
	SensitiveTargetCount   int    `json:"sensitiveTargetCount,omitempty"`
	CapturedAt             string `json:"capturedAt,omitempty"`
	AccessCount            int    `json:"accessCount,omitempty"`
	AccessEvidenceHash     string `json:"accessEvidenceHash,omitempty"`
	DataClassification     string `json:"dataClassification,omitempty"`
	FieldProjection        string `json:"fieldProjection,omitempty"`
}

type StartRuntimeValidationRequest struct {
	BuildId           string `json:"buildId,omitempty"`
	SuiteVersion      string `json:"suiteVersion,omitempty"`
	EnvironmentDigest string `json:"environmentDigest,omitempty"`
	ReplayDatasetId   string `json:"replayDatasetId,omitempty"`
	Persona           string `json:"persona,omitempty"`
}

type CompleteRuntimeValidationRequest struct {
	RequiredTests        int        `json:"requiredTests,omitempty"`
	RequiredFailures     int        `json:"requiredFailures,omitempty"`
	OptionalTests        int        `json:"optionalTests,omitempty"`
	OptionalFailures     int        `json:"optionalFailures,omitempty"`
	DeclaredCapabilities BooleanMap `json:"declaredCapabilities,omitempty"`
	ObservedCapabilities BooleanMap `json:"observedCapabilities,omitempty"`
	OptionalFailureCodes []string   `json:"optionalFailureCodes,omitempty"`
	PersonaConsistent    bool       `json:"personaConsistent,omitempty"`
}

type RuntimeValidation struct {
	ValidationId         string     `json:"validationId,omitempty"`
	BuildId              string     `json:"buildId,omitempty"`
	SuiteVersion         string     `json:"suiteVersion,omitempty"`
	EnvironmentDigest    string     `json:"environmentDigest,omitempty"`
	ReplayDatasetId      string     `json:"replayDatasetId,omitempty"`
	Persona              string     `json:"persona,omitempty"`
	State                string     `json:"state,omitempty"`
	RequiredTests        int        `json:"requiredTests,omitempty"`
	RequiredFailures     int        `json:"requiredFailures,omitempty"`
	OptionalTests        int        `json:"optionalTests,omitempty"`
	OptionalFailures     int        `json:"optionalFailures,omitempty"`
	DeclaredCapabilities BooleanMap `json:"declaredCapabilities,omitempty"`
	ObservedCapabilities BooleanMap `json:"observedCapabilities,omitempty"`
	OptionalFailureCodes []string   `json:"optionalFailureCodes,omitempty"`
	EvidenceHash         any        `json:"evidenceHash,omitempty"`
	RequestedBy          string     `json:"requestedBy,omitempty"`
	StartedAt            string     `json:"startedAt,omitempty"`
	CompletedAt          any        `json:"completedAt,omitempty"`
}

type CreateCostRateRequest struct {
	Region             string           `json:"region,omitempty"`
	ResourceTemplate   ResourceTemplate `json:"resourceTemplate,omitempty"`
	BaseHourlyUsd      float64          `json:"baseHourlyUsd,omitempty"`
	CpuCoreHourlyUsd   float64          `json:"cpuCoreHourlyUsd,omitempty"`
	MemoryGibHourlyUsd float64          `json:"memoryGibHourlyUsd,omitempty"`
	DesktopHourlyUsd   float64          `json:"desktopHourlyUsd,omitempty"`
	GpuHourlyUsd       float64          `json:"gpuHourlyUsd,omitempty"`
	MediaHourlyUsd     float64          `json:"mediaHourlyUsd,omitempty"`
	EffectiveAt        string           `json:"effectiveAt,omitempty"`
}

type CostRate struct {
	PricingVersion     string           `json:"pricingVersion,omitempty"`
	Region             string           `json:"region,omitempty"`
	ResourceTemplate   ResourceTemplate `json:"resourceTemplate,omitempty"`
	BaseHourlyUsd      float64          `json:"baseHourlyUsd,omitempty"`
	CpuCoreHourlyUsd   float64          `json:"cpuCoreHourlyUsd,omitempty"`
	MemoryGibHourlyUsd float64          `json:"memoryGibHourlyUsd,omitempty"`
	DesktopHourlyUsd   float64          `json:"desktopHourlyUsd,omitempty"`
	GpuHourlyUsd       float64          `json:"gpuHourlyUsd,omitempty"`
	MediaHourlyUsd     float64          `json:"mediaHourlyUsd,omitempty"`
	EffectiveAt        string           `json:"effectiveAt,omitempty"`
	CreatedBy          string           `json:"createdBy,omitempty"`
	CreatedAt          string           `json:"createdAt,omitempty"`
}

type SessionCostExplanation struct {
	SessionId        string           `json:"sessionId,omitempty"`
	NodeId           string           `json:"nodeId,omitempty"`
	Region           string           `json:"region,omitempty"`
	ResourceTemplate ResourceTemplate `json:"resourceTemplate,omitempty"`
	PricingVersion   string           `json:"pricingVersion,omitempty"`
	CpuMillis        int              `json:"cpuMillis,omitempty"`
	MemoryRequestMib int              `json:"memoryRequestMib,omitempty"`
	Desktop          bool             `json:"desktop,omitempty"`
	Gpu              bool             `json:"gpu,omitempty"`
	Media            bool             `json:"media,omitempty"`
	BaseHourlyUsd    float64          `json:"baseHourlyUsd,omitempty"`
	CpuHourlyUsd     float64          `json:"cpuHourlyUsd,omitempty"`
	MemoryHourlyUsd  float64          `json:"memoryHourlyUsd,omitempty"`
	DesktopHourlyUsd float64          `json:"desktopHourlyUsd,omitempty"`
	GpuHourlyUsd     float64          `json:"gpuHourlyUsd,omitempty"`
	MediaHourlyUsd   float64          `json:"mediaHourlyUsd,omitempty"`
	TotalHourlyUsd   float64          `json:"totalHourlyUsd,omitempty"`
	PricedAt         string           `json:"pricedAt,omitempty"`
}

type UpsertMediaQuotaRequest struct {
	MaxConcurrentStreams int `json:"maxConcurrentStreams,omitempty"`
	MaxBitrateKbps       int `json:"maxBitrateKbps,omitempty"`
}

type MediaQuota struct {
	TenantId             string `json:"tenantId,omitempty"`
	MaxConcurrentStreams int    `json:"maxConcurrentStreams,omitempty"`
	MaxBitrateKbps       int    `json:"maxBitrateKbps,omitempty"`
	ActiveStreams        int64  `json:"activeStreams,omitempty"`
	ActiveBitrateKbps    int64  `json:"activeBitrateKbps,omitempty"`
	UpdatedBy            string `json:"updatedBy,omitempty"`
	UpdatedAt            string `json:"updatedAt,omitempty"`
}

type UpsertSloPolicyRequest struct {
	AvailabilityTarget               float64 `json:"availabilityTarget,omitempty"`
	LatencyP95TargetMs               int     `json:"latencyP95TargetMs,omitempty"`
	WindowMinutes                    int     `json:"windowMinutes,omitempty"`
	ReleaseFreezeEnabled             bool    `json:"releaseFreezeEnabled,omitempty"`
	ReleaseFreezeBurnRateThreshold   float64 `json:"releaseFreezeBurnRateThreshold,omitempty"`
	ReleaseRecoveryBurnRateThreshold float64 `json:"releaseRecoveryBurnRateThreshold,omitempty"`
	ReleaseFreezeWindowMinutes       int     `json:"releaseFreezeWindowMinutes,omitempty"`
	ReleaseRecoveryStableMinutes     int     `json:"releaseRecoveryStableMinutes,omitempty"`
}

type RecordServiceLevelEventRequest struct {
	EventType       string `json:"eventType,omitempty"`
	DurationSeconds int    `json:"durationSeconds,omitempty"`
	LatencyP95Ms    any    `json:"latencyP95Ms,omitempty"`
	Source          string `json:"source,omitempty"`
	OccurredAt      string `json:"occurredAt,omitempty"`
	ExclusionCode   any    `json:"exclusionCode,omitempty"`
}

type UpsertSlaExclusionRequest struct {
	Description string `json:"description,omitempty"`
	Enabled     bool   `json:"enabled,omitempty"`
}

type SlaExclusion struct {
	TenantId      string `json:"tenantId,omitempty"`
	ExclusionCode string `json:"exclusionCode,omitempty"`
	Description   string `json:"description,omitempty"`
	Enabled       bool   `json:"enabled,omitempty"`
	UpdatedBy     string `json:"updatedBy,omitempty"`
	UpdatedAt     string `json:"updatedAt,omitempty"`
}

type ErrorBudget struct {
	TenantId                    string  `json:"tenantId,omitempty"`
	AvailabilityTarget          float64 `json:"availabilityTarget,omitempty"`
	LatencyP95TargetMs          int     `json:"latencyP95TargetMs,omitempty"`
	WindowMinutes               int     `json:"windowMinutes,omitempty"`
	AllowedUnavailableSeconds   int64   `json:"allowedUnavailableSeconds,omitempty"`
	ConsumedUnavailableSeconds  int64   `json:"consumedUnavailableSeconds,omitempty"`
	RemainingUnavailableSeconds int64   `json:"remainingUnavailableSeconds,omitempty"`
	BurnRatio                   float64 `json:"burnRatio,omitempty"`
	State                       string  `json:"state,omitempty"`
	WindowStartedAt             string  `json:"windowStartedAt,omitempty"`
	CalculatedAt                string  `json:"calculatedAt,omitempty"`
}

type ReleaseFreeze struct {
	TenantId                  string  `json:"tenantId,omitempty"`
	Enabled                   bool    `json:"enabled,omitempty"`
	Phase                     string  `json:"phase,omitempty"`
	Frozen                    bool    `json:"frozen,omitempty"`
	CurrentBurnRate           float64 `json:"currentBurnRate,omitempty"`
	FreezeBurnRateThreshold   float64 `json:"freezeBurnRateThreshold,omitempty"`
	RecoveryBurnRateThreshold float64 `json:"recoveryBurnRateThreshold,omitempty"`
	EvaluationWindowMinutes   int     `json:"evaluationWindowMinutes,omitempty"`
	RecoveryStableMinutes     int     `json:"recoveryStableMinutes,omitempty"`
	ReasonCode                string  `json:"reasonCode,omitempty"`
	StableSince               any     `json:"stableSince,omitempty"`
	FrozenAt                  any     `json:"frozenAt,omitempty"`
	ClearedAt                 any     `json:"clearedAt,omitempty"`
	EvaluatedAt               string  `json:"evaluatedAt,omitempty"`
	Version                   int64   `json:"version,omitempty"`
}

type UpsertRetentionPolicyRequest struct {
	DataClass       string `json:"dataClass,omitempty"`
	RetentionDays   int    `json:"retentionDays,omitempty"`
	LegalHold       bool   `json:"legalHold,omitempty"`
	ResidencyRegion string `json:"residencyRegion,omitempty"`
}

type RetentionPolicy struct {
	TenantId        string `json:"tenantId,omitempty"`
	DataClass       string `json:"dataClass,omitempty"`
	RetentionDays   int    `json:"retentionDays,omitempty"`
	LegalHold       bool   `json:"legalHold,omitempty"`
	ResidencyRegion string `json:"residencyRegion,omitempty"`
	UpdatedBy       string `json:"updatedBy,omitempty"`
	UpdatedAt       string `json:"updatedAt,omitempty"`
}

type CreateDeletionReceiptRequest struct {
	DataClass     string `json:"dataClass,omitempty"`
	ObjectId      string `json:"objectId,omitempty"`
	ContentDigest string `json:"contentDigest,omitempty"`
}

type DeletionReceipt struct {
	ReceiptId       string `json:"receiptId,omitempty"`
	TenantId        string `json:"tenantId,omitempty"`
	DataClass       string `json:"dataClass,omitempty"`
	ObjectId        string `json:"objectId,omitempty"`
	ContentDigest   string `json:"contentDigest,omitempty"`
	PolicyUpdatedAt string `json:"policyUpdatedAt,omitempty"`
	ReceiptHash     string `json:"receiptHash,omitempty"`
	DeletedBy       string `json:"deletedBy,omitempty"`
	DeletedAt       string `json:"deletedAt,omitempty"`
}

type UpsertLicenseInventoryRequest struct {
	ComponentType    string `json:"componentType,omitempty"`
	ComponentName    string `json:"componentName,omitempty"`
	ComponentVersion string `json:"componentVersion,omitempty"`
	LicenseId        string `json:"licenseId,omitempty"`
	SourceUrl        string `json:"sourceUrl,omitempty"`
	Approved         bool   `json:"approved,omitempty"`
}

type LicenseInventory struct {
	ComponentId      string `json:"componentId,omitempty"`
	ComponentType    string `json:"componentType,omitempty"`
	ComponentName    string `json:"componentName,omitempty"`
	ComponentVersion string `json:"componentVersion,omitempty"`
	LicenseId        string `json:"licenseId,omitempty"`
	SourceUrl        string `json:"sourceUrl,omitempty"`
	Approved         bool   `json:"approved,omitempty"`
	EvidenceHash     string `json:"evidenceHash,omitempty"`
	UpdatedBy        string `json:"updatedBy,omitempty"`
	UpdatedAt        string `json:"updatedAt,omitempty"`
}

type AuditExportManifest struct {
	ExportId           string `json:"exportId,omitempty"`
	TenantId           string `json:"tenantId,omitempty"`
	FromSequence       int64  `json:"fromSequence,omitempty"`
	ToSequence         int64  `json:"toSequence,omitempty"`
	EventCount         int64  `json:"eventCount,omitempty"`
	FirstEventHash     string `json:"firstEventHash,omitempty"`
	LastEventHash      string `json:"lastEventHash,omitempty"`
	ManifestHash       string `json:"manifestHash,omitempty"`
	SignatureAlgorithm string `json:"signatureAlgorithm,omitempty"`
	SigningKeyId       string `json:"signingKeyId,omitempty"`
	Signature          string `json:"signature,omitempty"`
	GeneratedBy        string `json:"generatedBy,omitempty"`
	GeneratedAt        string `json:"generatedAt,omitempty"`
}

type UpsertRegionRequest struct {
	Role                  string `json:"role,omitempty"`
	AdmissionState        string `json:"admissionState,omitempty"`
	ReplicationLagSeconds int    `json:"replicationLagSeconds,omitempty"`
}

type EnterpriseRegion struct {
	RegionId              string `json:"regionId,omitempty"`
	Role                  string `json:"role,omitempty"`
	AdmissionState        string `json:"admissionState,omitempty"`
	ReplicationLagSeconds int    `json:"replicationLagSeconds,omitempty"`
	LastVerifiedAt        string `json:"lastVerifiedAt,omitempty"`
	UpdatedBy             string `json:"updatedBy,omitempty"`
}

type StartRecoveryGameDayRequest struct {
	Scenario         string `json:"scenario,omitempty"`
	SourceRegion     string `json:"sourceRegion,omitempty"`
	TargetRegion     string `json:"targetRegion,omitempty"`
	RtoTargetSeconds int    `json:"rtoTargetSeconds,omitempty"`
	RpoTargetSeconds int    `json:"rpoTargetSeconds,omitempty"`
}

type CompleteRecoveryGameDayRequest struct {
	ObservedRtoSeconds int `json:"observedRtoSeconds,omitempty"`
	ObservedRpoSeconds int `json:"observedRpoSeconds,omitempty"`
	DataLossRecords    int `json:"dataLossRecords,omitempty"`
}

type RecoveryGameDay struct {
	GameDayId          string `json:"gameDayId,omitempty"`
	Scenario           string `json:"scenario,omitempty"`
	SourceRegion       string `json:"sourceRegion,omitempty"`
	TargetRegion       string `json:"targetRegion,omitempty"`
	State              string `json:"state,omitempty"`
	RtoTargetSeconds   int    `json:"rtoTargetSeconds,omitempty"`
	RpoTargetSeconds   int    `json:"rpoTargetSeconds,omitempty"`
	ObservedRtoSeconds any    `json:"observedRtoSeconds,omitempty"`
	ObservedRpoSeconds any    `json:"observedRpoSeconds,omitempty"`
	DataLossRecords    any    `json:"dataLossRecords,omitempty"`
	EvidenceHash       any    `json:"evidenceHash,omitempty"`
	StartedBy          string `json:"startedBy,omitempty"`
	StartedAt          string `json:"startedAt,omitempty"`
	CompletedAt        any    `json:"completedAt,omitempty"`
}

type ComplianceSnapshot struct {
	SnapshotId      string     `json:"snapshotId,omitempty"`
	TenantId        string     `json:"tenantId,omitempty"`
	Framework       string     `json:"framework,omitempty"`
	ControlCount    int        `json:"controlCount,omitempty"`
	PassingControls int        `json:"passingControls,omitempty"`
	EvidenceHash    string     `json:"evidenceHash,omitempty"`
	Evidence        BooleanMap `json:"evidence,omitempty"`
	GeneratedBy     string     `json:"generatedBy,omitempty"`
	GeneratedAt     string     `json:"generatedAt,omitempty"`
}

type EnterpriseOverview struct {
	Validations       []RuntimeValidation `json:"validations,omitempty"`
	CostRates         []CostRate          `json:"costRates,omitempty"`
	MediaQuota        any                 `json:"mediaQuota,omitempty"`
	ErrorBudget       any                 `json:"errorBudget,omitempty"`
	ReleaseFreeze     any                 `json:"releaseFreeze,omitempty"`
	SlaExclusions     []SlaExclusion      `json:"slaExclusions,omitempty"`
	RetentionPolicies []RetentionPolicy   `json:"retentionPolicies,omitempty"`
	LicenseInventory  []LicenseInventory  `json:"licenseInventory,omitempty"`
	Regions           []EnterpriseRegion  `json:"regions,omitempty"`
	RecoveryGameDays  []RecoveryGameDay   `json:"recoveryGameDays,omitempty"`
	LatestCompliance  any                 `json:"latestCompliance,omitempty"`
	GeneratedAt       string              `json:"generatedAt,omitempty"`
}

type BooleanMap struct {
	AdditionalProperties map[string]any `json:"-"`
}

type Error struct {
	Code      string         `json:"code,omitempty"`
	Message   string         `json:"message,omitempty"`
	Details   map[string]any `json:"details,omitempty"`
	RequestId string         `json:"requestId,omitempty"`
	Timestamp string         `json:"timestamp,omitempty"`
}
