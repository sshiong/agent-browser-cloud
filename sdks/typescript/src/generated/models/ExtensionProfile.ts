/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ExtensionProfile = {
    extensionId: string;
    displayName: string;
    staticCpuWeight: number;
    staticMemoryWeight: number;
    startupWeight: number;
    pageInjectionWeight: number;
    serviceWorkerWeight: number;
    cryptoWeight: number;
    networkWeight: number;
    observedMultiplier: number;
    confidence: number;
    profileState: 'PROBATION' | 'OBSERVED' | 'CERTIFIED' | 'DISABLED';
    web3: boolean;
    serviceWorker: boolean;
    crypto: boolean;
    privileged: boolean;
    samples: number;
    p95CpuMillis?: number | null;
    p95MemoryMib?: number | null;
    lastProfiledAt?: string | null;
    samplingTier: 'LOW' | 'MEDIUM' | 'HIGH' | 'DEEP';
    samplingCpuBudgetMillis: number;
    nextSampleAt: string | null;
    updatedAt: string;
};
