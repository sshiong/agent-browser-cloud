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
    privacyScanVersion: string;
    /**
     * SHA-256 of normalized local OCR output; OCR plaintext is never submitted.
     */
    ocrTextHash: string;
    /**
     * Sensitive PII pattern classes detected before local pixel redaction.
     */
    detectedSensitivePatternCount: number;
    /**
     * OCR line regions masked in local memory before model access.
     */
    piiRedactedRegionCount: number;
    /**
     * Must be zero after the mandatory second local OCR pass.
     */
    remainingSensitivePatternCount: number;
};
