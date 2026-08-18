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
    updatedAt: string;
};
