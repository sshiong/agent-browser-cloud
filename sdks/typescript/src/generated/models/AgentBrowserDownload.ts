/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type AgentBrowserDownload = {
    downloadId: string;
    filename: string;
    mimeType: string;
    size?: number | null;
    receivedBytes: number;
    progress?: number | null;
    status: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELED' | 'INTERRUPTED';
    startedAt: string;
    updatedAt: string;
};
