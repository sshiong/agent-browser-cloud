import type { ResourceStreamConnectionState } from './session';

export type WorkspaceOverview = {
  sessions: {
    total: number;
    running: number;
    pending: number;
    unhealthy: number;
    hibernated: number;
    terminated: number;
  };
  operations: { active: number };
  browserNodes: {
    visible: boolean;
    total: number;
    ready: number;
    constrained: number;
    activeSessions: number;
    maximumSessions: number;
    reservedCpuMillis: number;
    certifiedCpuMillis: number;
    reservedMemoryMib: number;
    certifiedMemoryMib: number;
  };
  proxies: { activeAllocations: number; boundSessions: number };
  agents: {
    active: number;
    awaitingHuman: number;
    pausedByResourcePolicy: number;
    failedLast24Hours: number;
  };
  cost: {
    currentHourlyUsd: number;
    activeSessionsWithoutCurrentPrice: number;
  };
  security: { warningLast24Hours: number; criticalLast24Hours: number };
  cursor: number;
  generatedAt: string;
};

export type WorkspaceOverviewChangeType =
  | 'SESSION'
  | 'OPERATION'
  | 'AGENT_TASK'
  | 'RESOURCE_EVENT'
  | 'BROWSER_NODE'
  | 'PROXY'
  | 'COST'
  | 'SECURITY';

export type WorkspaceOverviewEvent = {
  sequence: number;
  changeType: WorkspaceOverviewChangeType;
  occurredAt: string;
  replayed: boolean;
};

export type WorkspaceOverviewStreamControl = {
  cursor: number;
  resetRequired: boolean;
  connectedAt: string;
};

export type WorkspaceOverviewConnectionState = ResourceStreamConnectionState;
