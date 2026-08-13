/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type Recording = {
    recordingId: string;
    nodeId: string;
    segmentCount: number;
    frameCount: number;
    droppedFrames: number;
    redactedFrameCount: number;
    redactedRegionCount: number;
    redactionPolicyVersion: number;
    manifestSha256: string;
    manifestBytes: number;
    startedAt: string;
    endedAt: string;
    retentionUntil: string;
    legalHold: boolean;
};
