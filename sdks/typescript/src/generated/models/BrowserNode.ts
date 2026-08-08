/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type BrowserNode = {
    nodeId: string;
    region: string;
    grpcTarget: string;
    lifecycleState: 'READY' | 'DRAINING' | 'OFFLINE';
    admissionState: 'OPEN' | 'CLOSED';
    certifiedCpuMillis: number;
    certifiedMemoryMib: number;
    certifiedPidCount: number;
    certifiedGpuSlots: number;
    certifiedMediaSlots: number;
    safetyMarginPercent: number;
    reservedCpuMillis: number;
    reservedMemoryMib: number;
    reservedPidCount: number;
    reservedGpuSlots: number;
    reservedMediaSlots: number;
    activeSessions: number;
    maxSessions: number;
    memoryPsiSomeAvg10: number;
    memoryPsiFullAvg10: number;
    cpuPsiSomeAvg10: number;
    ioPsiFullAvg10: number;
    pressureState: 'NORMAL' | 'DEGRADED' | 'CRITICAL';
    pressureReason?: string | null;
    supportsDesktop: boolean;
    supportsGpu: boolean;
    supportsMedia: boolean;
    supportsNativeOs: boolean;
    isolationCapable: boolean;
    labels: Record<string, string>;
    lastHeartbeatAt: string;
    updatedAt: string;
};
