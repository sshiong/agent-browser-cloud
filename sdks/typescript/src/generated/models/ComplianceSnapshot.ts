/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BooleanMap } from './BooleanMap.js';
export type ComplianceSnapshot = {
    snapshotId: string;
    tenantId: string;
    framework: string;
    controlCount: number;
    passingControls: number;
    evidenceHash: string;
    evidence: BooleanMap;
    generatedBy: string;
    generatedAt: string;
};
