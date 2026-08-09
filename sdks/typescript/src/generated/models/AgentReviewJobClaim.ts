/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentReviewJob } from './AgentReviewJob.js';
import type { AgentReviewPayload } from './AgentReviewPayload.js';
export type AgentReviewJobClaim = {
    claimToken: string;
    job: AgentReviewJob;
    reviewPayload: AgentReviewPayload;
    leaseExpiresAt: string;
    claimEpoch: number;
};
