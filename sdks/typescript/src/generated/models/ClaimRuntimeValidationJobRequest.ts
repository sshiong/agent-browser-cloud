/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type ClaimRuntimeValidationJobRequest = {
    browserEngine: string;
    browserVersions: Array<string>;
    operatingSystem: string;
    architecture: string;
    capabilities: BooleanMap;
};
