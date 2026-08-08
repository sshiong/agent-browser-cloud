/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProxyRoutingCandidateScore } from './ProxyRoutingCandidateScore.js';
export type ProxyRoutingDecision = {
    sessionId: string;
    bindingProfileId: string;
    providerId: string;
    selectionMode: 'EXPLICIT' | 'AUTO';
    routingScore?: number | null;
    qualityScore?: number | null;
    reputationScore?: number | null;
    costPerGibUsd?: number | null;
    activeReservations?: number | null;
    maxConcurrentSessions?: number | null;
    /**
     * Total persisted candidates. Session list projections may omit candidateScores.
     */
    candidateCount: number;
    candidateScores: Array<ProxyRoutingCandidateScore>;
    selectedAt: string;
};
