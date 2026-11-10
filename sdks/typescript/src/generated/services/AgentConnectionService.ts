/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentConnectionCreateRequest } from '../models/AgentConnectionCreateRequest.js';
import type { AgentConnectionIdentity } from '../models/AgentConnectionIdentity.js';
import type { AgentConnectionIssued } from '../models/AgentConnectionIssued.js';
import type { AgentConnectionMutation } from '../models/AgentConnectionMutation.js';
import type { AgentConnectionQuota } from '../models/AgentConnectionQuota.js';
import type { AgentConnectionQuotaUpdate } from '../models/AgentConnectionQuotaUpdate.js';
import type { AgentConnectionView } from '../models/AgentConnectionView.js';
import type { AgentEnvironmentCreation } from '../models/AgentEnvironmentCreation.js';
import type { AgentEnvironmentList } from '../models/AgentEnvironmentList.js';
import type { AgentManagedOperation } from '../models/AgentManagedOperation.js';
import type { BatchDeleteSessionsResponse } from '../models/BatchDeleteSessionsResponse.js';
import type { CreateSessionRequest } from '../models/CreateSessionRequest.js';
import type { OperationResponse } from '../models/OperationResponse.js';
import type { SessionView } from '../models/SessionView.js';
import type { CancelablePromise } from '../core/CancelablePromise.js';
import type { BaseHttpRequest } from '../core/BaseHttpRequest.js';
export class AgentConnectionService {
    constructor(public readonly httpRequest: BaseHttpRequest) {}
    /**
     * listAgentConnections
     * @returns AgentConnectionView Successful result
     * @throws ApiError
     */
    public listAgentConnections({
        xTenantId,
    }: {
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<Array<AgentConnectionView>> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-connections',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * createAgentConnection
     * @returns AgentConnectionIssued Successful result
     * @throws ApiError
     */
    public createAgentConnection({
        requestBody,
        xTenantId,
    }: {
        requestBody: AgentConnectionCreateRequest,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentConnectionIssued> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-connections',
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * configureAgentConnectionQuota
     * @returns AgentConnectionMutation Successful result
     * @throws ApiError
     */
    public configureAgentConnectionQuota({
        connectionId,
        requestBody,
        xTenantId,
    }: {
        connectionId: string,
        requestBody: AgentConnectionQuotaUpdate,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentConnectionMutation> {
        return this.httpRequest.request({
            method: 'PATCH',
            url: '/api/v1/agent-connections/{connectionId}',
            path: {
                'connectionId': connectionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * revokeAgentConnection
     * @returns AgentConnectionMutation Successful result
     * @throws ApiError
     */
    public revokeAgentConnection({
        connectionId,
        xTenantId,
    }: {
        connectionId: string,
        /**
         * Local/Test identity adapter only. Ignored in Production, where tenant identity is derived from the authenticated JWT.
         */
        xTenantId?: string,
    }): CancelablePromise<AgentConnectionMutation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-connections/{connectionId}:revoke',
            path: {
                'connectionId': connectionId,
            },
            headers: {
                'X-Tenant-Id': xTenantId,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * getAgentConnectionIdentity
     * @returns AgentConnectionIdentity Successful result
     * @throws ApiError
     */
    public getAgentConnectionIdentity(): CancelablePromise<AgentConnectionIdentity> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-access/identity',
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * getAgentConnectionQuota
     * @returns AgentConnectionQuota Successful result
     * @throws ApiError
     */
    public getAgentConnectionQuota(): CancelablePromise<AgentConnectionQuota> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-access/quota',
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * listAgentEnvironments
     * @returns AgentEnvironmentList Successful result
     * @throws ApiError
     */
    public listAgentEnvironments({
        limit = 20,
        offset,
    }: {
        limit?: number,
        offset?: number,
    }): CancelablePromise<AgentEnvironmentList> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-access/environments',
            query: {
                'limit': limit,
                'offset': offset,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * createAgentEnvironment
     * @returns AgentEnvironmentCreation Successful result
     * @throws ApiError
     */
    public createAgentEnvironment({
        idempotencyKey,
        requestBody,
    }: {
        idempotencyKey: string,
        requestBody: CreateSessionRequest,
    }): CancelablePromise<AgentEnvironmentCreation> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-access/environments',
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            body: requestBody,
            mediaType: 'application/json',
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * getAgentEnvironment
     * @returns SessionView Successful result
     * @throws ApiError
     */
    public getAgentEnvironment({
        sessionId,
    }: {
        sessionId: string,
    }): CancelablePromise<SessionView> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-access/environments/{sessionId}',
            path: {
                'sessionId': sessionId,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * deleteAgentEnvironment
     * @returns BatchDeleteSessionsResponse Successful result
     * @throws ApiError
     */
    public deleteAgentEnvironment({
        sessionId,
        idempotencyKey,
    }: {
        sessionId: string,
        idempotencyKey: string,
    }): CancelablePromise<BatchDeleteSessionsResponse> {
        return this.httpRequest.request({
            method: 'DELETE',
            url: '/api/v1/agent-access/environments/{sessionId}',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * getAgentManagedOperation
     * @returns AgentManagedOperation Successful result
     * @throws ApiError
     */
    public getAgentManagedOperation({
        operationId,
    }: {
        operationId: string,
    }): CancelablePromise<AgentManagedOperation> {
        return this.httpRequest.request({
            method: 'GET',
            url: '/api/v1/agent-access/operations/{operationId}',
            path: {
                'operationId': operationId,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * startAgentEnvironment
     * @returns OperationResponse Successful result
     * @throws ApiError
     */
    public startAgentEnvironment({
        sessionId,
        idempotencyKey,
    }: {
        sessionId: string,
        idempotencyKey: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-access/environments/{sessionId}:start',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
    /**
     * stopAgentEnvironment
     * @returns OperationResponse Successful result
     * @throws ApiError
     */
    public stopAgentEnvironment({
        sessionId,
        idempotencyKey,
    }: {
        sessionId: string,
        idempotencyKey: string,
    }): CancelablePromise<OperationResponse> {
        return this.httpRequest.request({
            method: 'POST',
            url: '/api/v1/agent-access/environments/{sessionId}:stop',
            path: {
                'sessionId': sessionId,
            },
            headers: {
                'Idempotency-Key': idempotencyKey,
            },
            errors: {
                401: `Authentication required`,
                403: `Connection, role or tool access rejected`,
                409: `Quota, lifecycle or identity conflict`,
            },
        });
    }
}
