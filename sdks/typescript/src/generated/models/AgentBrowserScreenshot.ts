/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentBrowserScreenshotMode } from './AgentBrowserScreenshotMode.js';
import type { AgentBrowserScreenshotRegion } from './AgentBrowserScreenshotRegion.js';
export type AgentBrowserScreenshot = {
    screenshotId: string;
    sessionId: string;
    mode: AgentBrowserScreenshotMode;
    state: 'EXECUTING' | 'COMMITTED' | 'FAILED';
    expectedStateCursor: string;
    capturedStateCursor?: string | null;
    activeTabId: string;
    elementId?: string | null;
    region?: (AgentBrowserScreenshotRegion | null);
    coordinateSpace?: 'VIEWPORT' | 'DOCUMENT';
    viewportWidth?: number | null;
    viewportHeight?: number | null;
    deviceScaleFactor?: number | null;
    evidenceId?: string | null;
    accessGrantId?: string | null;
    accessGrantExpiresAt?: string | null;
    contentSha256?: string | null;
    contentBytes?: number | null;
    redactionState?: 'MASKED' | 'NOT_REQUIRED';
    redactedRegionCount?: number | null;
    errorCode?: string | null;
    requestId: string;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
