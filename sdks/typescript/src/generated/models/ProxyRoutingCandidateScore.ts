/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyRoutingCandidateScore = {
    bindingProfileId: string;
    providerId: string;
    routingScore: number;
    qualityScore: number;
    reputationScore: number;
    costPerGibUsd: number;
    costScore: number;
    regionScore: number;
    headroomScore: number;
    /**
     * Tenant/Binding-scoped score learned only from independently verified outcomes; neutral until five samples. Absent on N-1 servers.
     */
    businessOutcomeScore?: number;
    businessOutcomeSampleCount?: number;
    /**
     * The candidate matches the most recent healthy route for the same Browser Profile. Absent on N-1 servers.
     */
    profileSticky?: boolean;
    /**
     * The candidate has fewer than twenty verified outcome samples; selection remains constrained by health, capacity, quality and score distance.
     */
    explorationEligible?: boolean;
    activeReservations: number;
    maxConcurrentSessions: number;
};
