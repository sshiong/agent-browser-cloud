/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBatchActionInput = {
    actionId: string;
    toolId: 'CLICK_TARGET' | 'TYPE_TEXT' | 'FILL' | 'PASTE_AGENT_CLIPBOARD' | 'SCROLL' | 'WAIT_FOR';
    targetRef: string | null;
    /**
     * Stable structured identity used to rebind this primitive after earlier actions advance targetRevision.
     */
    elementId: string | null;
    targetRevision: number | null;
    payloadHash: string | null;
    payloadLength: number | null;
    dataClass: string | null;
    scrollDeltaY: number | null;
    waitCondition: string | null;
    timeoutMs: number | null;
    sensitiveTargetAuthorized: boolean;
    maximumAttempts: number;
};
