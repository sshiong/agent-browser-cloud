/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResourceTemplate } from './ResourceTemplate.js';
export type SessionCostExplanation = {
    sessionId: string;
    nodeId: string;
    region: string;
    resourceTemplate: ResourceTemplate;
    pricingVersion: string;
    cpuMillis: number;
    memoryRequestMib: number;
    desktop: boolean;
    gpu: boolean;
    media: boolean;
    baseHourlyUsd: number;
    cpuHourlyUsd: number;
    memoryHourlyUsd: number;
    desktopHourlyUsd: number;
    gpuHourlyUsd: number;
    mediaHourlyUsd: number;
    totalHourlyUsd: number;
    pricedAt: string;
};
