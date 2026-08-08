/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceTemplate } from './ResourceTemplate.js';
export type BrowserPlacement = {
    sessionId: string;
    tenantId: string;
    nodeId: string;
    requestedTemplate: ResourceTemplate;
    resolvedTemplate: ResourceTemplate;
    extensionIds: Array<string>;
    unknownExtensionCount: number;
    cpuMillis: number;
    memoryRequestMib: number;
    memoryLimitMib: number;
    pidLimit: number;
    tabBudget: number;
    stateCollectorBudgetPercent: number;
    remoteDesktopBitrateKbps: number;
    extensionCpuWeight: number;
    requiresDesktop: boolean;
    requiresGpu: boolean;
    requiresNativeOs: boolean;
    requiresIsolation: boolean;
    requiresMedia: boolean;
    mediaSlots: number;
    mediaEncoderSlots: number;
    backgroundTabsFrozen: boolean;
    newTabsBlocked: boolean;
    pausedExtensionIds: Array<string>;
    /**
     * Node-acknowledged sampling percentage for optional successful command traces. Failures and mandatory evidence are not sampled.
     */
    successTraceSamplePercent: number;
    /**
     * Node-acknowledged sampling percentage for successful Agent screenshots. Failed actions and navigation remain mandatory.
     */
    successScreenshotSamplePercent: number;
    /**
     * Node-acknowledged maximum Observer forwarding rate. It is zero only for Sessions without a desktop data plane.
     */
    observerFrameRateFps: number;
    videoRecordingRequested: boolean;
    /**
     * Current Node-acknowledged state of the independent CDP recording actuator.
     */
    videoRecordingEnabled: boolean;
    mediaBitrateKbps: number;
    placementScore: number;
    state: 'RESERVED' | 'ACTIVE' | 'EVICTING' | 'RELEASED';
    reasonCodes: Array<string>;
    reservedAt: string;
    activatedAt?: string | null;
    releasedAt?: string | null;
};
