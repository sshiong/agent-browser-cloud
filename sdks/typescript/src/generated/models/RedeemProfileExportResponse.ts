/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RedeemProfileExportResponse = {
    grantId: string;
    profileId: string;
    checkpointId: string;
    archiveSha256: string;
    archiveSizeBytes: number;
    /**
     * Purpose-bound URL for a BrowserCloud Profile AEAD v1 encrypted archive. The downloaded object uses the .tar.zst.enc suffix and can be imported only where its versioned archive encryption key remains available.
     */
    downloadUrl: string;
    expiresAt: string;
};
