export type AgentRiskClass =
  | 'R0_READ_ONLY'
  | 'R1_LOW_RISK_CHANGE'
  | 'R2_DATA_CHANGE'
  | 'R3_ACCOUNT_CHANGE'
  | 'R4_FINANCIAL'
  | 'R5_SECURITY';

export type AgentToolId =
  | 'NAVIGATE'
  | 'GET_CURRENT_STATE'
  | 'CLICK_TARGET'
  | 'DOUBLE_CLICK_TARGET'
  | 'RIGHT_CLICK_TARGET'
  | 'HOVER_TARGET'
  | 'CLEAR_TARGET'
  | 'CHECK_TARGET'
  | 'UNCHECK_TARGET'
  | 'TYPE_TEXT'
  | 'FILL'
  | 'PASTE_AGENT_CLIPBOARD'
  | 'SCROLL'
  | 'WAIT_FOR'
  | 'OPEN_TAB'
  | 'SWITCH_TAB'
  | 'CLOSE_TAB'
  | 'ACCEPT_DIALOG'
  | 'DISMISS_DIALOG'
  | 'EXECUTE_ACTIONS'
  | 'GET_URL'
  | 'GET_PAGE_SUMMARY'
  | 'REQUEST_HUMAN_TAKEOVER';

export type InstructionSourceType =
  | 'APPLICATION_DATA'
  | 'EMAIL'
  | 'DOCUMENT'
  | 'WEB_CONTENT'
  | 'THIRD_PARTY_WIDGET';

export type AgentActionDataClass = 'PUBLIC' | 'PII' | 'CREDENTIAL' | 'OTP';
export type AgentWaitCondition =
  'STATE_CHANGED' | 'STATE_STABLE' | 'TARGET_PRESENT';

export interface AgentBatchActionRequest {
  toolId:
    | 'CLICK_TARGET'
    | 'DOUBLE_CLICK_TARGET'
    | 'RIGHT_CLICK_TARGET'
    | 'HOVER_TARGET'
    | 'CLEAR_TARGET'
    | 'CHECK_TARGET'
    | 'UNCHECK_TARGET'
    | 'TYPE_TEXT'
    | 'FILL'
    | 'PASTE_AGENT_CLIPBOARD'
    | 'SCROLL'
    | 'WAIT_FOR'
    | 'OPEN_TAB'
    | 'SWITCH_TAB'
    | 'CLOSE_TAB'
    | 'ACCEPT_DIALOG'
    | 'DISMISS_DIALOG';
  targetRef?: string;
  targetRevision?: number;
  value?: string;
  secretId?: string;
  dataClass?: AgentActionDataClass;
  scrollDeltaY?: number;
  waitCondition?: AgentWaitCondition;
  timeoutMs?: number;
  tabId?: string;
  tabUrl?: string;
  dialogId?: string;
}

export interface CreateAgentActionRequest extends Omit<
  AgentBatchActionRequest,
  'toolId'
> {
  toolId:
    | AgentBatchActionRequest['toolId']
    | 'EXECUTE_ACTIONS'
    | 'REQUEST_HUMAN_TAKEOVER';
  actions?: AgentBatchActionRequest[];
  stopOnError?: boolean;
}

export interface ExecuteAgentBrowserActionsRequest {
  goal: string;
  expectedStateCursor: string;
  actions: AgentBatchActionRequest[];
  stopOnError?: boolean;
}

export interface AgentBrowserSnapshot {
  stateCursor: string;
  state: import('./session').BrowserStateView;
  visibleTextSummary: string;
  tabs: Array<{ tabId: string; url: string; title: string; active: boolean }>;
  activeTab: {
    tabId: string;
    url: string;
    title: string;
    active: boolean;
  } | null;
  focusedElementId?: string;
  formControlElementIds: string[];
  dialogElementIds: string[];
  nativeDialogs: import('./session').BrowserNativeDialogView[];
  nativeDialogEvidenceFresh: boolean;
  pageLoadingState: 'loading' | 'interactive' | 'complete' | '';
  challengeState: 'NOT_EVALUATED';
  visionRecommended: boolean;
}

export interface AgentBrowserInspectRequest {
  stateCursor: string;
  elementIds: string[];
}

export interface AgentBrowserFindRequest {
  query: string;
  roles?: string[];
  includeHidden?: boolean;
  limit?: number;
}

export interface AgentBrowserTargetList {
  stateCursor: string;
  targets: import('./session').InteractiveTargetView[];
  truncated: boolean;
}

export interface CreateAgentTaskRequest {
  goal: string;
  startUrl?: string;
  allowedDomains: string[];
  maxActions?: number;
  replanBudget?: number;
  contextSources?: Array<{
    sourceId: string;
    sourceType: InstructionSourceType;
    classification: string;
    content: string;
  }>;
  actions?: CreateAgentActionRequest[];
}

export interface AgentTaskView {
  taskId: string;
  sessionId: string;
  goal: string;
  state:
    | 'PLANNED'
    | 'QUEUED'
    | 'AWAITING_REVIEW'
    | 'AWAITING_CONFIRMATION'
    | 'BLOCKED'
    | 'RUNNING'
    | 'WAITING_FOR_HUMAN'
    | 'PAUSED_BY_RESOURCE_POLICY'
    | 'COMPLETED'
    | 'FAILED';
  riskClass: AgentRiskClass;
  intentDecision: 'ALLOWED' | 'CONFIRM_REQUIRED' | 'FORBIDDEN';
  blockedReason?: string;
  agentPolicy?: import('./session').AgentPolicy;
  currentStep: number;
  totalSteps: number;
  replanCount: number;
  stepExecution: {
    pendingStepId?: string;
    pendingToolId?: AgentToolId;
    baseStateVersion?: number;
    baseContentHash?: string;
    deadline?: string;
    leaseUntil?: string;
    replanReason?: string;
  };
  executionWait?: {
    reason?: 'HUMAN_INPUT_PRIORITY';
    since?: string;
  };
  confirmation: {
    confirmationId?: string;
    status?: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
    expiresAt?: string;
    decidedAt?: string;
    actorId?: string;
    evidenceHash?: string;
  };
  humanHandoff: {
    requestId?: string;
    status?: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';
    expiresAt?: string;
    actorId?: string;
  };
  challengeEventId?: string;
  review?: {
    reviewId?: string;
    status:
      | 'NOT_REQUIRED'
      | 'PENDING'
      | 'QUEUED'
      | 'IN_REVIEW'
      | 'APPROVED'
      | 'REJECTED'
      | 'FAILED';
    decision?: 'APPROVE' | 'REJECT';
    reasonCodes: string[];
    planHash?: string;
    deploymentId?: string;
    modelName?: string;
    modelRevision?: string;
    inputTokens?: number;
    outputTokens?: number;
    costMicros?: number;
    latencyMs?: number;
    failureCode?: string;
    completedAt?: string;
  };
  allowedDomains: string[];
  plan: {
    intentId: string;
    steps: AgentPlanStep[];
    maxActions: number;
    replanBudget: number;
    expiresAt: string;
  };
  operationId?: string;
  executionResults: AgentToolExecutionResult[];
  lastError?: string;
  securityEvents: AgentSecurityEvent[];
  createdAt: string;
  updatedAt: string;
}

export interface AgentToolExecutionResult {
  stepId: string;
  toolId: AgentToolId;
  status: string;
  resultHash: string;
  output: Record<string, unknown>;
  verification: string;
  completedAt: string;
}

export interface AgentPlanStep {
  stepId: string;
  toolId: AgentToolId;
  riskClass: AgentRiskClass;
  targetUrl?: string;
  input?: {
    targetRef?: string;
    targetRevision?: number;
    payloadHash?: string;
    payloadLength?: number;
    dataClass?: AgentActionDataClass;
    scrollDeltaY?: number;
    waitCondition?: AgentWaitCondition;
    timeoutMs?: number;
    sensitiveTargetAuthorized?: boolean;
    maximumAttempts?: number;
    actions?: Array<{
      actionId: string;
      toolId: AgentBatchActionRequest['toolId'];
      targetRef?: string;
      targetRevision?: number;
      payloadHash?: string;
      payloadLength?: number;
      dataClass?: AgentActionDataClass;
      scrollDeltaY?: number;
      waitCondition?: AgentWaitCondition;
      timeoutMs?: number;
      sensitiveTargetAuthorized?: boolean;
      maximumAttempts?: number;
    }>;
    stopOnError?: boolean;
  };
  rationale: string;
  supportingSources: string[];
  trustFloor: 'TRUSTED' | 'RESTRICTED' | 'UNTRUSTED';
  taintLabels: string[];
  requiredConfirmation: boolean;
  strategy:
    | 'SEMANTIC_DOM'
    | 'ACCESSIBILITY'
    | 'DESKTOP_INPUT'
    | 'VISION_DESKTOP'
    | 'HUMAN_ASSIST'
    | 'HUMAN_TAKEOVER';
  requiredStateQuality: string;
  verification: string;
  capabilityTokenId: string;
}

export interface AgentSecurityEvent {
  eventId: string;
  eventType: string;
  severity: string;
  decision: string;
  ruleCode: string;
  sourceType: string;
  contentHash: string;
  createdAt: string;
}

export interface AgentTaskListResponse {
  items: AgentTaskView[];
  total: number;
  limit: number;
  offset: number;
}

export type AgentTaskSummary = Pick<
  AgentTaskView,
  | 'taskId'
  | 'sessionId'
  | 'goal'
  | 'state'
  | 'riskClass'
  | 'intentDecision'
  | 'blockedReason'
  | 'agentPolicy'
  | 'currentStep'
  | 'totalSteps'
  | 'createdAt'
  | 'updatedAt'
> & {
  securityEventCount: number;
  executionWaitReason?: 'HUMAN_INPUT_PRIORITY';
  executionWaitSince?: string;
};

export interface AgentTaskSummaryListResponse {
  items: AgentTaskSummary[];
  metrics: {
    planned: number;
    completed: number;
    blocked: number;
  };
  total: number;
  limit: number;
  nextCursor?: string | null;
  hasMore: boolean;
}
