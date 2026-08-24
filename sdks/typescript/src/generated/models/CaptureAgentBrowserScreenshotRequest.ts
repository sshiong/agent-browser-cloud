/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserScreenshotMode } from './AgentBrowserScreenshotMode.js';
import type { AgentBrowserScreenshotRegion } from './AgentBrowserScreenshotRegion.js';
/**
 * VIEWPORT and FULL_PAGE accept no target. ELEMENT requires elementId. REGION and CHALLENGE_REGION require a viewport-relative region. Other combinations are rejected.
 *
 */
export type CaptureAgentBrowserScreenshotRequest = {
    mode: AgentBrowserScreenshotMode;
    expectedStateCursor: string;
    elementId?: string | null;
    region?: (AgentBrowserScreenshotRegion | null);
};
