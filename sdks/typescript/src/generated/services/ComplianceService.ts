/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AuditExportManifest } from '../models/AuditExportManifest.js';
import type { ComplianceSnapshot } from '../models/ComplianceSnapshot.js';
import type { CreateDeletionReceiptRequest } from '../models/CreateDeletionReceiptRequest.js';
import type { DeletionReceipt } from '../models/DeletionReceipt.js';
import type { LicenseInventory } from '../models/LicenseInventory.js';
import type { RetentionPolicy } from '../models/RetentionPolicy.js';
import type { UpsertLicenseInventoryRequest } from '../models/UpsertLicenseInventoryRequest.js';
import type { UpsertRetentionPolicyRequest } from '../models/UpsertRetentionPolicyRequest.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class ComplianceService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * List tenant retention, residency and legal-hold policies
     * @returns RetentionPolicy Retention policies.
     * @throws ApiError
     */
    public listRetentionPolicies({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<Array<RetentionPolicy>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/retention-policies',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Configure a tenant data-class retention policy
     * @returns RetentionPolicy Retention policy.
     * @throws ApiError
     */
    public upsertRetentionPolicy({
        requestBody,
        xTenantId,
    }: {
        requestBody: UpsertRetentionPolicyRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<RetentionPolicy> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/retention-policies',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
            },
        });
    }
    /**
     * Delete an eligible retained object and generate a tamper-evident receipt
     * @returns DeletionReceipt Deletion receipt.
     * @throws ApiError
     */
    public createRetentionDeletionReceipt({
        requestBody,
    }: {
        requestBody: CreateDeletionReceiptRequest,
    }): CancelablePromise<DeletionReceipt> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/retention-deletion-receipts',
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
                409: `State or idempotency conflict.`,
            },
        });
    }
    /**
     * List Runtime, Extension, Service and SDK license evidence
     * @returns LicenseInventory License inventory.
     * @throws ApiError
     */
    public listLicenseInventory(): CancelablePromise<Array<LicenseInventory>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/enterprise/license-inventory',
            errors: {
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Upsert a license evidence record
     * @returns LicenseInventory License evidence.
     * @throws ApiError
     */
    public upsertLicenseInventory({
        componentId,
        requestBody,
    }: {
        componentId: string,
        requestBody: UpsertLicenseInventoryRequest,
    }): CancelablePromise<LicenseInventory> {
        return this.httpRequest.request({
            method: 'PUT',
            url: '/api/v1/enterprise/license-inventory/{componentId}',
            path: {
                'componentId': componentId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
            },
        });
    }
    /**
     * Generate a signed manifest for a contiguous tenant audit range
     * @returns AuditExportManifest Signed audit export manifest.
     * @throws ApiError
     */
    public generateAuditExportManifest({
        fromSequence,
        toSequence,
    }: {
        fromSequence?: number,
        toSequence?: number,
    }): CancelablePromise<AuditExportManifest> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/audit-exports',
            query: {
                'fromSequence': fromSequence,
                'toSequence': toSequence,
            },
            errors: {
                400: `Invalid request.`,
                403: `Resource is outside the caller tenant scope.`,
                404: `Resource not found.`,
            },
        });
    }
    /**
     * Generate a hash-bound tenant compliance snapshot
     * @returns ComplianceSnapshot Compliance snapshot.
     * @throws ApiError
     */
    public generateComplianceSnapshot({
        xTenantId,
        framework = 'SOC2',
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
        framework?: string,
    }): CancelablePromise<ComplianceSnapshot> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/enterprise/compliance-snapshots',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            query: {
                'framework': framework,
            },
            errors: {
                400: `Invalid request.`,
            },
        });
    }
}
