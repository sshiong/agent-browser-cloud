/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { RecordingCloudHold } from './RecordingCloudHold.js';
export type RetentionPolicy = {
    tenantId: string;
    dataClass: string;
    retentionDays: number;
    legalHold: boolean;
    residencyRegion: string;
    /**
     * Whether object version protection is requested; independent of its applied state.
     */
    cloudHoldRequired?: boolean;
    recordingCloudHold?: RecordingCloudHold;
    updatedBy: string;
    updatedAt: string;
};
