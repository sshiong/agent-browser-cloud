/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceTemplate } from './ResourceTemplate.js';
export type CostRate = {
    pricingVersion: string;
    region: string;
    resourceTemplate: ResourceTemplate;
    baseHourlyUsd: number;
    cpuCoreHourlyUsd: number;
    memoryGibHourlyUsd: number;
    desktopHourlyUsd: number;
    remoteDesktopEgressGibUsd: number;
    gpuHourlyUsd: number;
    mediaHourlyUsd: number;
    effectiveAt: string;
    createdBy: string;
    createdAt: string;
};
