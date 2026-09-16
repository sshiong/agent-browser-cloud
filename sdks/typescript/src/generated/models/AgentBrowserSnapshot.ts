/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserNativeDialog } from './AgentBrowserNativeDialog.js';
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
    tabs: Array<AgentBrowserTab>;
    /**
     * Null only while an N-1 Browser Node has not projected Browser-level tab authority.
     */
    activeTab: (AgentBrowserTab | null);
    focusedElementId: string | null;
    formControlElementIds: Array<string>;
    /**
     * DOM/A11y dialog-like elements only; separate from browser-native JavaScript Dialog state.
     */
    dialogElementIds: Array<string>;
    nativeDialogs: Array<AgentBrowserNativeDialog>;
    nativeDialogEvidenceFresh: boolean;
    pageLoadingState: 'loading' | 'interactive' | 'complete' | '';
    /**
     * Challenge authority remains the dedicated Challenge API; the snapshot does not infer it from DOM text.
     */
    challengeState: 'NOT_EVALUATED';
    /**
     * True when structured perception is depth-limited, a visible target is occluded, or a fresh visible opaque frame can only be observed through the governed bounded screenshot path.
     */
    visionRecommended: boolean;
};
