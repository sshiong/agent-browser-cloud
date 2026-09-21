/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpdateChallengeAutomationPolicyRequest = {
    controlMode?: 'SAFE' | 'AUTONOMOUS';
    sensitiveInputMaximumAttempts?: number;
    enabled: boolean;
    maximumAttempts: number;
    minimumConfidence: number;
    allowMultiClick: boolean;
    allowSlide: boolean;
    opaqueFrameClickEnabled?: boolean;
    opaqueFrameClickOrigins?: Array<string>;
    motionMinimumSteps?: number;
    motionMaximumSteps?: number;
    motionMinimumDelayMs?: number;
    motionMaximumDelayMs?: number;
    targetOffsetRatio?: number;
};
