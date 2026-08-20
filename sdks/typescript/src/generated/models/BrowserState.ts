/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserTab } from './AgentBrowserTab.js';
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
    /**
     * Browser-level Page Targets. Empty only while an N-1 Browser Node has not projected tab authority.
     */
    tabs: Array<AgentBrowserTab>;
    /**
     * ID of the one active tab; empty only when tabs is empty during rolling compatibility.
     */
    activeTabId: string;
};
