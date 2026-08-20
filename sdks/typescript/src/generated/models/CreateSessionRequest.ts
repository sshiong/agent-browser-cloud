/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentPolicy } from './AgentPolicy.js';
import type { ResourcePolicyRequest } from './ResourcePolicyRequest.js';
import type { SessionIdentitySpecInput } from './SessionIdentitySpecInput.js';
export type CreateSessionRequest = {
    /**
     * Backward-compatible request field. It must equal the authenticated tenant claim and cannot select a tenant.
     * @deprecated
     */
    tenantId: string;
    profileId: string;
    /**
     * Optional approved Runtime Build. Workspace default is bound when omitted.
     */
    runtimeBuildId?: string;
    /**
     * Optional enabled tenant Application Recovery Contract bound immutably at creation.
     */
    applicationId?: string;
    /**
     * Optional Workspace Group. Its AUTO defaults apply only when resourcePolicy is omitted.
     */
    groupId?: string;
    /**
     * Optional tenant-owned Workspace Tags assigned transactionally at creation.
     */
    tagIds?: Array<string>;
    region?: string;
    /**
     * Optional tenant Binding profile snapshotted immutably at Session creation.
     */
    proxyBindingProfileId?: string;
    resourcePolicy?: ResourcePolicyRequest;
    requestedTabs?: number;
    agentActionsPerMinute?: number;
    remoteDesktop?: boolean;
    /**
     * Optional create-time override. Workspace default is bound when omitted.
     */
    humanTakeoverEnabled?: boolean;
    /**
     * Optional immutable Agent policy. Defaults to BALANCED when omitted.
     */
    agentPolicy?: AgentPolicy;
    web3Workload?: boolean;
    mediaWorkload?: boolean;
    requestedMediaStreams?: number;
    mediaBitrateKbps?: number;
    /**
     * Enables independent CDP pixel recording. Segments are committed through Storage Helper and Object Storage.
     */
    videoRecording?: boolean;
    extensionIds?: Array<string>;
    metadata?: Record<string, string>;
    /**
     * Creation-time Browser identity. It becomes locked immediately after Session creation.
     */
    identitySpec?: SessionIdentitySpecInput;
};
