/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type UpsertLicenseInventoryRequest = {
    componentType: 'RUNTIME' | 'EXTENSION' | 'SERVICE' | 'SDK';
    componentName: string;
    componentVersion: string;
    licenseId: string;
    sourceUrl: string;
    approved: boolean;
};
