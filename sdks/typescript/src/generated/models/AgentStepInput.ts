/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBatchActionInput } from './AgentBatchActionInput.js';
export type AgentStepInput = {
    targetRef: string | null;
    targetRevision: number | null;
    /**
     * Hash of sealed input; plaintext and ciphertext are never returned.
     */
    payloadHash: string | null;
    payloadLength: number | null;
    dataClass: string | null;
    scrollDeltaY: number | null;
    waitCondition: string | null;
    timeoutMs: number | null;
    sensitiveTargetAuthorized: boolean;
    maximumAttempts: number;
    actions: Array<AgentBatchActionInput>;
    stopOnError: boolean;
    tabId: string | null;
    tabUrl: string | null;
    dialogId: string | null;
    endTargetRef: string | null;
    endElementId: string | null;
    key: string | null;
    button: number | null;
    deltaX: number | null;
    deltaY: number | null;
    durationMs: number | null;
};
