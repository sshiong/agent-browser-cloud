/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type CompleteRuntimeValidationRequest = {
    requiredTests: number;
    requiredFailures: number;
    optionalTests: number;
    optionalFailures: number;
    declaredCapabilities: BooleanMap;
    observedCapabilities: BooleanMap;
    optionalFailureCodes: Array<string>;
    personaConsistent: boolean;
};
