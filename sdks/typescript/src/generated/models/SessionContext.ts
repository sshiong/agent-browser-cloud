/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceTemplate } from './ResourceTemplate.js';
import type { SessionState } from './SessionState.js';
export type SessionContext = {
    sessionId: string;
    tenantId: string;
    profileId: string;
    nodeId?: string | null;
    runtimeBuildId?: string | null;
    isolationProfileId?: string | null;
    /**
     * Runtime allocation identity. It is never reusable across Sessions.
     */
    proxyBindingId?: string | null;
    coordinatorTerm: number;
    contextEpoch: number;
    browserGeneration: number;
    networkRevision: number;
    resourceTemplate: ResourceTemplate;
    state: SessionState;
    policyHash: string;
    createdAt: string;
    updatedAt: string;
};
