/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ChallengeVisualJob } from './ChallengeVisualJob.js';
export type ChallengeVisualJobClaim = {
    claimToken: string;
    job: ChallengeVisualJob;
    screenshotUrl: string;
    screenshotExpiresAt: string;
    challengeType: 'SINGLE_CLICK' | 'IMAGE_SELECTION' | 'PUZZLE' | 'MULTI_ROUND';
    targetSummary: string;
    allowMultiClick: boolean;
    allowSlide: boolean;
    minimumConfidence: number;
};
