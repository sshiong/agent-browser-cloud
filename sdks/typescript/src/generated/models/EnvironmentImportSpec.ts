/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AgentPolicy } from './AgentPolicy.js';
import type { ResourcePolicyRequest } from './ResourcePolicyRequest.js';
export type EnvironmentImportSpec = {
    displayName: string;
    description?: string | null;
    profileId: string;
    runtimeBuildId?: string | null;
    applicationId?: string | null;
    groupId?: string | null;
    tagIds?: any[] | null;
    region?: string | null;
    resourcePolicy?: (ResourcePolicyRequest | null);
    requestedTabs?: number;
    agentActionsPerMinute?: number;
    remoteDesktop?: boolean;
    humanTakeoverEnabled?: boolean | null;
    agentPolicy?: (AgentPolicy | null);
    web3Workload?: boolean;
    mediaWorkload?: boolean;
    requestedMediaStreams?: number;
    mediaBitrateKbps?: number;
    videoRecording?: boolean;
    extensionIds?: any[] | null;
};
