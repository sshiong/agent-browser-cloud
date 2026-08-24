/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBrowserFileUpload = {
    uploadId: string;
    operationId: string;
    sessionId: string;
    targetRef: string;
    filename: string;
    mimeType: string;
    contentSha256: string;
    contentBytes: number;
    state: 'STAGING' | 'EXECUTING' | 'COMMITTED' | 'FAILED';
    errorCode?: string | null;
    stateVersionAfter?: number | null;
    requestId: string;
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
};
