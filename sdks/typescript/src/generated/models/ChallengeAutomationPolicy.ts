/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ChallengeAutomationPolicy = {
    sessionId: string;
    controlMode: 'SAFE' | 'AUTONOMOUS';
    sensitiveInputMaximumAttempts: number;
    enabled: boolean;
    maximumAttempts: number;
    minimumConfidence: number;
    allowMultiClick: boolean;
    allowSlide: boolean;
    /**
     * Explicit opt-in for one bounded click inside an allowlisted opaque Challenge frame. It never authorizes text, secrets, slides, payments or account decisions.
     */
    opaqueFrameClickEnabled: boolean;
    /**
     * Exact origin-only allowlist. HTTPS is required except for local/private HTTP development origins.
     */
    opaqueFrameClickOrigins: Array<string>;
    motionMinimumSteps: number;
    motionMaximumSteps: number;
    motionMinimumDelayMs: number;
    motionMaximumDelayMs: number;
    targetOffsetRatio: number;
    updatedAt: string;
};
