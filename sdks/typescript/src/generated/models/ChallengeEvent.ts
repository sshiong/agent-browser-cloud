/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ChallengeEvent = {
    challengeEventId: string;
    sessionId: string;
    contextEpoch: number;
    stateVersion: number;
    targetRevision: number;
    confidence: number;
    /**
     * Bounded signal codes and hashes; no screenshot, OTP or raw sensitive content.
     */
    evidence: Record<string, any>;
    suspectedType: 'SINGLE_CLICK' | 'IMAGE_SELECTION' | 'PUZZLE' | 'OTP' | 'DEVICE_CONFIRMATION' | 'MULTI_ROUND' | 'USER_JUDGMENT' | 'PAYMENT_CONFIRMATION' | 'UNKNOWN';
    accessOutcome: 'CHALLENGE_SUSPECTED' | 'CHALLENGE_CONFIRMED';
    targetRef: string | null;
    targetSummary: string;
    status: 'SUSPECTED' | 'CONFIRMED' | 'AUTHORIZED' | 'EXECUTING' | 'RESOLVED' | 'FAILED' | 'EXPIRED' | 'SUPERSEDED' | 'TAKEOVER_REQUIRED';
    oneClickEligible: boolean;
    detectedAt: string;
    authorizationDeadline: string;
    expiresAt: string;
    updatedAt: string;
};
