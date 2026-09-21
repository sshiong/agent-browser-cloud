/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecordingPlaybackPurpose } from './RecordingPlaybackPurpose.js';
export type RecordingPlaybackGrant = {
    grantId: string;
    sessionId: string;
    recordingId: string;
    purpose: RecordingPlaybackPurpose;
    state: 'ISSUED' | 'REDEEMING' | 'REDEEMED' | 'FAILED';
    expiresAt: string;
    createdAt: string;
    redeemedAt?: string | null;
    errorCode?: string | null;
    requestId?: string | null;
};
