/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { EvidencePurpose } from './EvidencePurpose.js';
export type EvidenceCapture = {
    captureId: string;
    sessionId: string;
    purpose: EvidencePurpose;
    state: 'EXECUTING' | 'COMMITTED' | 'FAILED';
    evidenceId?: string | null;
    errorCode?: string | null;
    commandId: string;
    requestId?: string | null;
    createdAt: string;
    completedAt?: string | null;
};
