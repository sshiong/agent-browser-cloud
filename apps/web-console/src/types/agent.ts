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
  | 'TYPE_TEXT'
  | 'SCROLL'
  | 'WAIT_FOR'
  | 'GET_URL'
  | 'GET_PAGE_SUMMARY'
  | 'REQUEST_HUMAN_TAKEOVER';

export type InstructionSourceType =
  | 'APPLICATION_DATA'
  | 'EMAIL'
  | 'DOCUMENT'
  | 'WEB_CONTENT'
  | 'THIRD_PARTY_WIDGET';

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
}

export interface AgentTaskView {
  taskId: string;
  sessionId: string;
  goal: string;
  state: 'PLANNED' | 'BLOCKED';
  riskClass: AgentRiskClass;
  intentDecision: 'ALLOWED' | 'CONFIRM_REQUIRED' | 'FORBIDDEN';
  blockedReason?: string;
  currentStep: number;
  totalSteps: number;
  allowedDomains: string[];
  plan: {
    intentId: string;
    steps: AgentPlanStep[];
    maxActions: number;
    replanBudget: number;
    expiresAt: string;
  };
  securityEvents: AgentSecurityEvent[];
  createdAt: string;
  updatedAt: string;
}

export interface AgentPlanStep {
  stepId: string;
  toolId: AgentToolId;
  riskClass: AgentRiskClass;
  targetUrl?: string;
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
