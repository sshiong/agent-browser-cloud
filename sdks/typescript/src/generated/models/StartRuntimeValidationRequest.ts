/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type StartRuntimeValidationRequest = {
    buildId: string;
    suiteVersion: string;
    environmentDigest: string;
    replayDatasetId: string;
    persona: string;
    browserEngine?: string;
    browserVersion?: string;
    operatingSystem?: string;
    architecture?: string;
    requiredWorkerCapabilities?: BooleanMap;
    maximumAttempts?: number;
};
