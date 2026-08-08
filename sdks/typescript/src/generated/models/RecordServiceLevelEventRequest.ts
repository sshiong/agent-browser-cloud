/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecordServiceLevelEventRequest = {
    eventType: 'UNAVAILABLE' | 'LATENCY_BREACH' | 'HEALTHY';
    durationSeconds: number;
    latencyP95Ms: number | null;
    source: string;
    occurredAt: string;
    exclusionCode?: string | null;
};
