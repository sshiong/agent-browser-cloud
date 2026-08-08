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
    targets: Array<InteractiveTarget>;
};
