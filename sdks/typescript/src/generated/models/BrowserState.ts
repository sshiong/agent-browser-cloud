/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { InteractiveTarget } from './InteractiveTarget.js';
export type BrowserState = {
    sessionId: string;
    contextEpoch: number;
    stateVersion: number;
    targetRevision: number;
    url: string;
    title: string;
    stateHash: string;
    stateQuality: 'COMPLETE' | 'DEPTH_LIMITED' | 'RESYNCING' | 'DEGRADED' | 'INVALID';
    /**
     * Browser document.readyState; empty only for legacy or unavailable evidence.
     */
    documentReadyState: 'loading' | 'interactive' | 'complete' | '';
    /**
     * Continuously observed quiet time; zero while requests are active or evidence is unavailable.
     */
    networkQuietMillis: number;
    /**
     * True only while the Browser-level and Page Network observers remain authoritative.
     */
    networkEvidenceFresh: boolean;
    targets: Array<InteractiveTarget>;
};
