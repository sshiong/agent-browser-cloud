/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type RuntimeValidationMatrixCellRequest = {
    environmentDigest: string;
    browserEngine: string;
    browserVersion: string;
    operatingSystem: string;
    architecture: string;
    requiredWorkerCapabilities: BooleanMap;
    maximumAttempts?: number;
};
