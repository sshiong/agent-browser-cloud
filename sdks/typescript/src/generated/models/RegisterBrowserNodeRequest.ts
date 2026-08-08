/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RegisterBrowserNodeRequest = {
    region: string;
    grpcTarget: string;
    certifiedCpuMillis: number;
    certifiedMemoryMib: number;
    certifiedPidCount: number;
    certifiedGpuSlots: number;
    certifiedMediaSlots?: number;
    safetyMarginPercent: number;
    maxSessions: number;
    supportsDesktop: boolean;
    supportsGpu: boolean;
    supportsMedia?: boolean;
    supportsNativeOs: boolean;
    isolationCapable: boolean;
    labels?: Record<string, string>;
};
