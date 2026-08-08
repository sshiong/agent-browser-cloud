/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RedeemEvidenceAccessResponse = {
    grantId: string;
    evidenceId: string;
    /**
     * Ephemeral exact-object URL; never persisted by the Control Plane.
     */
    downloadUrl: string;
    expiresAt: string;
};
