/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProxyBindingHealth } from './ProxyBindingHealth.js';
export type ProxyBinding = {
    bindingProfileId: string;
    name: string;
    description?: string | null;
    providerId: string;
    region?: string | null;
    expectedExitIp: string;
    credentialConfigured: boolean;
    enabled: boolean;
    healthState: ProxyBindingHealth;
    lastVerifiedExitIp?: string | null;
    lastHealthCheckedAt?: string | null;
    /**
     * Bounded error code only; Provider or credential details are never returned.
     */
    lastFailureReason?: string | null;
    probeSampleCount: number;
    /**
     * Lifetime success ratio of persisted runtime-bind and active exit probes.
     */
    probeSuccessRatePercent?: number | null;
    /**
     * Active exit probe latency EWMA with alpha 0.2.
     */
    latencyEwmaMs?: number | null;
    /**
     * Transparent 80% availability EWMA plus 20% latency EWMA score.
     */
    qualityScore?: number | null;
    /**
     * After this instant the UI must label the last observation as stale.
     */
    healthFreshUntil?: string | null;
    /**
     * HEALTHY becomes UNHEALTHY only after three consecutive active probe failures.
     */
    consecutiveFailures: number;
    version: number;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
};
