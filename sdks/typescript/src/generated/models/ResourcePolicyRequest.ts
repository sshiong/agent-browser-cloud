/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ExecutionEnvironment } from './ExecutionEnvironment.js';
import type { MaximumReachedPolicy } from './MaximumReachedPolicy.js';
export type ResourcePolicyRequest = {
    mode: string;
    onMaximumReached?: MaximumReachedPolicy;
    allowMigration?: boolean;
    allowHibernate?: boolean;
    blockMigrationDuringHumanTakeover?: boolean;
    executionEnvironment?: ExecutionEnvironment;
    minimumTemplate?: 'standard-v1' | 'interactive-v1' | 'heavy-v1' | 'native-standard-v1';
    maximumCpuMillis?: number;
    maximumMemoryMib?: number;
    maximumCostPerHour?: number;
    scaleUpWindowSeconds?: number;
    scaleDownWindowSeconds?: number;
    adjustmentCooldownSeconds?: number;
};
