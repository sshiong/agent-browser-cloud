/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
/**
 * Component-level quiet windows from consecutive authoritative Node samples. No DOM text, selectors, URL, or user value is exposed.
 */
export type PageStability = {
    domQuietMillis: number;
    layoutQuietMillis: number;
    focusQuietMillis: number;
    routeQuietMillis: number;
    /**
     * False for N-1 Nodes, native-dialog-blocked samples, region-only resync, or an interrupted Page observation.
     */
    evidenceFresh: boolean;
};
