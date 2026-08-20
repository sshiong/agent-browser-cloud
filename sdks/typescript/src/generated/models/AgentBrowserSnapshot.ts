/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserTab } from './AgentBrowserTab.js';
import type { BrowserState } from './BrowserState.js';
export type AgentBrowserSnapshot = {
    /**
     * Monotonic State/Target revision plus authoritative content hash.
     */
    stateCursor: string;
    state: BrowserState;
    /**
     * Bounded non-sensitive summary; never contains password or OTP values.
     */
    visibleTextSummary: string;
    activeTab: AgentBrowserTab;
    focusedElementId: string | null;
    formControlElementIds: Array<string>;
    dialogElementIds: Array<string>;
    pageLoadingState: 'loading' | 'interactive' | 'complete' | '';
    /**
     * Challenge authority remains the dedicated Challenge API; the snapshot does not infer it from DOM text.
     */
    challengeState: 'NOT_EVALUATED';
    /**
     * True only when structured perception is depth-limited or a visible target is occluded.
     */
    visionRecommended: boolean;
};
