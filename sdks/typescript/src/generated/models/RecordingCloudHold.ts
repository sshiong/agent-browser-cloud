/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Aggregate evidence for the managed, undeleted Recording set only. VERIFIED requires every managed Recording to match desiredMode; it makes no claim about unmanaged or externally modified objects.
 */
export type RecordingCloudHold = {
    status: 'LOGICAL_ONLY' | 'NOT_REQUESTED' | 'NO_RECORDINGS' | 'PENDING' | 'UNKNOWN' | 'VERIFIED';
    desiredMode: 'ON' | 'OFF';
    pendingRecordings: number;
    unknownRecordings: number;
    verifiedOnRecordings: number;
    verifiedOffRecordings: number;
    /**
     * At least one UNKNOWN request has exhausted its bounded reconciliation budget; protection must not be released based on elapsed time.
     */
    reconciliationExhausted: boolean;
};
