/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentOutcomeJob } from './AgentOutcomeJob.js';
import type { AgentOutcomePayload } from './AgentOutcomePayload.js';
export type AgentOutcomeJobClaim = {
    claimToken: string;
    job: AgentOutcomeJob;
    outcomePayload: AgentOutcomePayload;
    leaseExpiresAt: string;
    claimEpoch: number;
};
