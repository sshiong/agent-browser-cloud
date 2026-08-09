/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
import type { RuntimeValidationJob } from './RuntimeValidationJob.js';
export type RuntimeValidation = {
    validationId: string;
    buildId: string;
    suiteVersion: string;
    environmentDigest: string;
    replayDatasetId: string;
    persona: string;
    state: 'RUNNING' | 'PASSED' | 'DEGRADED' | 'FAILED';
    requiredTests: number;
    requiredFailures: number;
    optionalTests: number;
    optionalFailures: number;
    declaredCapabilities: BooleanMap;
    observedCapabilities: BooleanMap;
    optionalFailureCodes: Array<string>;
    evidenceHash: string | null;
    requestedBy: string;
    startedAt: string;
    completedAt: string | null;
    job?: (RuntimeValidationJob | null);
};
