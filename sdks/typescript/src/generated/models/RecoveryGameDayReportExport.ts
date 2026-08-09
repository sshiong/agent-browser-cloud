/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RecoveryGameDayReportExport = {
    exportId: string;
    gameDayId: string;
    reportFormat: 'JSON';
    eventCount: number;
    report: Record<string, any>;
    reportHash: string;
    signatureAlgorithm: 'HMAC-SHA256';
    signingKeyId: string;
    signature: string;
    generatedBy: string;
    generatedAt: string;
};
