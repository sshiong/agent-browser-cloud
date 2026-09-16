/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserScreenshotMode } from './AgentBrowserScreenshotMode.js';
import type { AgentBrowserScreenshotRegion } from './AgentBrowserScreenshotRegion.js';
/**
 * VIEWPORT and FULL_PAGE accept no target. ELEMENT requires a structured elementId. OPAQUE_FRAME requires its frameRef in elementId and derives the region server-side from fresh Browser State; it is observation-only and cannot authorize input. REGION and CHALLENGE_REGION require a viewport-relative region. Other combinations are rejected.
 *
 */
export type CaptureAgentBrowserScreenshotRequest = {
    mode: AgentBrowserScreenshotMode;
    expectedStateCursor: string;
    /**
     * Stable element ID for ELEMENT, or an ofr_ frameRef for OPAQUE_FRAME.
     */
    elementId?: string | null;
    region?: (AgentBrowserScreenshotRegion | null);
};
