/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type CompleteKeyRotationRequest = {
    newKeyWriteVerified: boolean;
    oldKeyReadVerified: boolean;
    plaintextRejected: boolean;
    affectedWorkloads: number;
    verificationReference: string;
};
