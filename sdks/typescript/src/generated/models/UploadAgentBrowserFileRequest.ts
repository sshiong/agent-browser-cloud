/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UploadAgentBrowserFileRequest = {
    targetRef: string;
    targetRevision: number;
    baseStateVersion: number;
    baseContentHash: string;
    /**
     * Safe display filename; path separators and control characters are rejected.
     */
    filename: string;
    mimeType: string;
    contentSha256: string;
    file: Blob;
};
