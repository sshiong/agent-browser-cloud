/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AuditExportManifest = {
    exportId: string;
    tenantId: string;
    fromSequence: number;
    toSequence: number;
    eventCount: number;
    firstEventHash: string;
    lastEventHash: string;
    manifestHash: string;
    signatureAlgorithm: 'HMAC-SHA256';
    signingKeyId: string;
    signature: string;
    generatedBy: string;
    generatedAt: string;
};
