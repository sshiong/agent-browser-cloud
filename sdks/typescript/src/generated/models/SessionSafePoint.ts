/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { SafePointBlocker } from './SafePointBlocker.js';
export type SessionSafePoint = {
    sessionId: string;
    safe: boolean;
    state: 'SAFE' | 'BLOCKED' | 'UNKNOWN';
    dataFreshness: 'LIVE' | 'STALE' | 'MISSING' | 'NOT_REQUIRED';
    nodeId?: string | null;
    contextEpoch: number;
    evaluatedAt: string;
    lastNodeObservationAt?: string | null;
    blockers: Array<SafePointBlocker>;
};
