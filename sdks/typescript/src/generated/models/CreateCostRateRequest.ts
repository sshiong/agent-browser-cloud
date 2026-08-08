/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceTemplate } from './ResourceTemplate.js';
export type CreateCostRateRequest = {
    region: string;
    resourceTemplate: ResourceTemplate;
    baseHourlyUsd: number;
    cpuCoreHourlyUsd: number;
    memoryGibHourlyUsd: number;
    desktopHourlyUsd: number;
    gpuHourlyUsd: number;
    mediaHourlyUsd: number;
    effectiveAt: string;
};
