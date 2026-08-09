/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type RuntimeValidationJob = {
    validationId: string;
    browserEngine: string;
    browserVersion: string;
    operatingSystem: string;
    architecture: string;
    requiredWorkerCapabilities: BooleanMap;
    state: 'QUEUED' | 'CLAIMED' | 'EXECUTING' | 'ACKED' | 'COMMITTED' | 'FAILED';
    attempt: number;
    maximumAttempts: number;
    workerId: string | null;
    claimEpoch: number;
    availableAt: string;
    leaseExpiresAt: string | null;
    lastHeartbeatAt: string | null;
    failureCode: string | null;
    resultHash: string | null;
    updatedAt: string;
};
