/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type RuntimeBuild = {
    buildId: string;
    engine: string;
    version: string;
    platform: string;
    securityTier: string;
    regressionStatus: string;
    releaseChannel: 'UNRELEASED' | 'CANARY' | 'STABLE' | 'DISABLED';
    signatureVerified: boolean;
    signature?: string | null;
    artifactDigest?: string | null;
    signingKeyId?: string | null;
    sbomUrl?: string | null;
    validatedAt?: string | null;
    releasedAt?: string | null;
    disabledAt?: string | null;
    disabledBy?: string | null;
    createdAt: string;
};
