/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecordingPlaybackSegment } from './RecordingPlaybackSegment.js';
export type RecordingPlaybackAccess = {
    grantId: string;
    recordingId: string;
    manifestSha256: string;
    frameCount: number;
    redactedFrameCount: number;
    redactedRegionCount: number;
    redactionPolicyVersion: number;
    /**
     * Expiry shared by the signed URLs in this response page.
     */
    expiresAt: string;
    /**
     * End of the actor-bound playback session; new pages are rejected afterward.
     */
    accessExpiresAt: string;
    nextSegmentOffset?: number | null;
    segments: Array<RecordingPlaybackSegment>;
};
