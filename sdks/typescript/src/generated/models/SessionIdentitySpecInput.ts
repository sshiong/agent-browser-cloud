/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type SessionIdentitySpecInput = {
    userAgent?: string | null;
    timezone?: string | null;
    locale?: string | null;
    languages?: Array<string>;
    webRtcPolicy?: 'DEFAULT' | 'DISABLED' | 'PROXY_ONLY';
    dnsPolicy?: 'SYSTEM' | 'PROXY';
    viewportWidth?: number | null;
    viewportHeight?: number | null;
    screenWidth?: number | null;
    screenHeight?: number | null;
    deviceScaleFactor?: number | null;
    /**
     * Must be installed on the selected Browser Node; chromium-standard-v1 is built in.
     */
    fingerprintProfile?: string | null;
    /**
     * Must match a Node runtime profile; linux-desktop-v1 is built in.
     */
    operatingSystemProfile?: string | null;
};
