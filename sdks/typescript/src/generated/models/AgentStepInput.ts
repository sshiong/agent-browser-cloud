/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
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
};
