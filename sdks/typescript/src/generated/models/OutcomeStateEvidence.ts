/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { OutcomeTargetEvidence } from './OutcomeTargetEvidence.js';
export type OutcomeStateEvidence = {
    stateVersion: number;
    targetRevision: number;
    stateHash: string;
    /**
     * Query, fragment and user-info are removed.
     */
    url: string;
    /**
     * Redacted title.
     */
    title: string;
    stateQuality: string;
    documentReadyState: string;
    networkQuietMillis: number;
    networkEvidenceFresh: boolean;
    observedAt: string;
    targets: Array<OutcomeTargetEvidence>;
};
