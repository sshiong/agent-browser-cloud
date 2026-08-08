/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SecureDebugSnapshot = {
    debugSessionId: string;
    sessionId: string;
    sessionState: string;
    runtimeBuildId: string | null;
    contextEpoch: number;
    browserGeneration: number;
    networkRevision: number;
    /**
     * Origin only. Path, query, fragment and user information are excluded.
     */
    urlOrigin: string | null;
    stateVersion: number;
    targetRevision: number;
    stateQuality: string;
    stateHash: string | null;
    interactiveTargetCount: number;
    sensitiveTargetCount: number;
    capturedAt: string;
    accessCount: number;
    accessEvidenceHash: string;
    dataClassification: 'SENSITIVE_MINIMIZED';
    fieldProjection: string;
};
