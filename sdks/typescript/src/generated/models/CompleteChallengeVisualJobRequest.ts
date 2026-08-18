/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ChallengeVisualAction } from './ChallengeVisualAction.js';
export type CompleteChallengeVisualJobRequest = {
    claimToken: string;
    decision: 'ACT' | 'ESCALATE';
    actions: Array<ChallengeVisualAction>;
    confidence: number;
    deploymentId: string;
    modelRevision: string;
    providerRequestId?: string | null;
    inputTokens: number;
    outputTokens: number;
    latencyMs: number;
    outputHash: string;
};
