/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentPolicy } from './AgentPolicy.js';
import type { OperationView } from './OperationView.js';
import type { ResourceTemplate } from './ResourceTemplate.js';
import type { SessionState } from './SessionState.js';
import type { WorkspaceTagSummary } from './WorkspaceTagSummary.js';
export type SessionView = {
    sessionId: string;
    displayName: string;
    tenantId: string;
    profileId: string;
    groupId?: string | null;
    tags?: Array<WorkspaceTagSummary>;
    /**
     * Immutable create-time capability binding. Old Control Planes may omit it during rolling upgrades.
     */
    humanTakeoverEnabled?: boolean;
    /**
     * Immutable Agent capability and budget policy. Old Control Planes may omit it during rolling upgrades.
     */
    agentPolicy?: AgentPolicy;
    /**
     * Immutable normalized Extension IDs bound at Session creation. Old Control Planes may omit it during rolling upgrades.
     */
    extensionIds?: Array<string>;
    region: string;
    resourceTemplate: ResourceTemplate;
    state: SessionState;
    nodeId?: string | null;
    runtimeBuildId?: string | null;
    /**
     * Runtime allocation identity. It is never reusable across Sessions.
     */
    proxyBindingId?: string | null;
    /**
     * Reusable management profile selected at creation; distinct from proxyBindingId.
     */
    proxyBindingProfileId?: string | null;
    contextEpoch: number;
    browserGeneration: number;
    currentOperation?: (OperationView | null);
    createdAt: string;
    updatedAt: string;
};
