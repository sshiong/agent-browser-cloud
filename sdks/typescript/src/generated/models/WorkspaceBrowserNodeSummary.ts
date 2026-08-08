/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type WorkspaceBrowserNodeSummary = {
    /**
     * True only when the caller may inspect platform-wide Node capacity.
     */
    visible: boolean;
    total: number;
    ready: number;
    constrained: number;
    activeSessions: number;
    maximumSessions: number;
    reservedCpuMillis: number;
    certifiedCpuMillis: number;
    reservedMemoryMib: number;
    certifiedMemoryMib: number;
};
