/* ========================================
   Agent Browser Cloud — Domain Types
   ======================================== */

export type SessionState =
  | 'created'
  | 'starting'
  | 'running'
  | 'idle'
  | 'human_controlled'
  | 'degraded'
  | 'recovering'
  | 'hibernated'
  | 'stopping'
  | 'stopped'
  | 'failed';

export type HealthStatus = 'healthy' | 'warning' | 'critical' | 'unknown';

export type OperationMode =
  | 'agent_interactive'
  | 'human_takeover'
  | 'human_assist'
  | 'snapshot'
  | 'quiesce'
  | 'recovery'
  | 'proxy_transition'
  | 'termination';

export interface Session {
  id: string;
  name: string;
  group: string;
  tags: string[];
  state: SessionState;
  runtime: RuntimeInfo;
  profile: ProfileInfo;
  proxy: ProxyInfo;
  agent: AgentInfo;
  node: string;
  extensions: number;
  resourceClass: string;
  lastActivity: string;
  createdAt: string;
  browserGeneration: number;
  contextEpoch: number;
  currentOperation?: OperationInfo;
}

export interface RuntimeInfo {
  id: string;
  name: string;
  version: string;
  buildId: string;
  securityTier: string;
}

export interface ProfileInfo {
  id: string;
  name: string;
  coreSize: string;
  lastCheckpoint: string;
  encrypted: boolean;
}

export interface ProxyInfo {
  id: string;
  provider: string;
  region: string;
  country: string;
  ip: string;
  type: string;
  latency: number;
  health: HealthStatus;
}

export interface AgentInfo {
  status: 'idle' | 'running' | 'waiting_human' | 'paused' | 'failed';
  currentStep?: number;
  totalSteps?: number;
  goal?: string;
}

export interface OperationInfo {
  id: string;
  mode: OperationMode;
  owner: string;
  phase: string;
  startedAt: string;
}

export interface BrowserNode {
  id: string;
  name: string;
  region: string;
  cpu: number;
  memory: number;
  sessions: number;
  maxSessions: number;
  status: HealthStatus;
}

export interface ProxyProvider {
  id: string;
  name: string;
  type: string;
  regions: string[];
  successRate: number;
  avgLatency: number;
  costPerGb: number;
  status: HealthStatus;
}

export interface RuntimeBuild {
  id: string;
  name: string;
  chromiumVersion: string;
  buildId: string;
  platform: string;
  securityTier: string;
  validationStatus: 'passed' | 'failed' | 'pending' | 'unknown';
  installed: boolean;
  isDefault: boolean;
}

export interface Profile {
  id: string;
  name: string;
  tenantId: string;
  coreSize: string;
  cacheSize: string;
  lastCheckpoint: string;
  encryptionKeyVersion: number;
  restoreStatus: 'ready' | 'restoring' | 'failed' | 'unknown';
  runtimeCompatibility: string[];
}

export interface Extension {
  id: string;
  name: string;
  description: string;
  version: string;
  category: string;
  icon: string;
  securityClass: 'standard' | 'high_risk' | 'privileged' | 'unknown';
  resourceWeight: number;
  installedSessions: number;
  installed: boolean;
}

export interface AgentTask {
  id: string;
  name: string;
  goal: string;
  sessionId: string;
  sessionName: string;
  state: 'pending' | 'running' | 'waiting_human' | 'completed' | 'failed';
  currentStep: number;
  totalSteps: number;
  risk: 'low' | 'medium' | 'high';
  cost: number;
  startedAt: string;
  result?: string;
}

export interface TimelineEvent {
  id: string;
  time: string;
  severity: 'info' | 'warning' | 'error' | 'critical';
  component: string;
  event: string;
  sessionId?: string;
  sessionName?: string;
  details?: string;
}

export interface OverviewMetrics {
  runningSessions: number;
  idleSessions: number;
  failedSessions: number;
  totalNodes: number;
  activeAgentTasks: number;
  proxyAvailability: number;
  profileStorage: string;
  todayCost: number;
}
