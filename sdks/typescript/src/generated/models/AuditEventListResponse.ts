/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuditEvent } from './AuditEvent.js';
export type AuditEventListResponse = {
    items: Array<AuditEvent>;
    total: number;
    chainValid: boolean;
    headHash: string | null;
};
