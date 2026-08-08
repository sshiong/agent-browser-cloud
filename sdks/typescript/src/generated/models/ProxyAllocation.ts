/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type ProxyAllocation = {
    allocationId: string;
    sessionId: string;
    providerId: string;
    protocol: 'HTTP';
    state: 'ALLOCATED' | 'BOUND' | 'RELEASED' | 'FAILED';
    exitIp?: string | null;
    country?: string | null;
    asn?: string | null;
    allocatedAt: string;
    verifiedAt?: string | null;
    releasedAt?: string | null;
    updatedAt: string;
};
