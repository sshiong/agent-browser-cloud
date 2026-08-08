/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EnvironmentImportItem } from './EnvironmentImportItem.js';
import type { EnvironmentImportState } from './EnvironmentImportState.js';
export type EnvironmentImport = {
    importId: string;
    name: string;
    schemaVersion: number;
    manifestHash: string;
    state: EnvironmentImportState;
    totalCount: number;
    readyCount: number;
    succeededCount: number;
    items: Array<EnvironmentImportItem>;
    createdAt: string;
    updatedAt: string;
    committedAt?: string | null;
    version: number;
};
