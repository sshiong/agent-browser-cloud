/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentClipboardBridge = {
    bridgeId: string;
    sessionId: string;
    direction: 'USER_TO_AGENT' | 'AGENT_TO_USER';
    purpose: 'OPERATOR_COPY' | 'AUTOMATION_HANDOFF' | 'HUMAN_ASSISTANCE';
    connectionId: string;
    state: 'ISSUED' | 'COMPLETED' | 'EXPIRED';
    agentClipboardVersion: number;
    contentHash: string;
    valueLength: number;
    /**
     * Returned only while an AGENT_TO_USER delivery is ISSUED and unexpired.
     */
    value: string | null;
    expiresAt: string;
    completedAt: string | null;
    createdAt: string;
};
