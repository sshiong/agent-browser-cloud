/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceAdjustment } from './ResourceAdjustment.js';
import type { ResourcePolicy } from './ResourcePolicy.js';
import type { ResourcePolicyStatus } from './ResourcePolicyStatus.js';
export type SessionResource = {
    sessionId: string;
    policy: ResourcePolicy;
    allocation?: any | null;
    usage?: any | null;
    usageSamples: Array<Record<string, any>>;
    cost?: any | null;
    currentAdjustment?: (ResourceAdjustment | null);
    status: ResourcePolicyStatus;
    statusReason?: string | null;
    dataFreshness: 'LIVE' | 'STALE' | 'AWAITING_TELEMETRY';
    lastEvaluatedAt?: string | null;
    lastAdjustedAt?: string | null;
};
