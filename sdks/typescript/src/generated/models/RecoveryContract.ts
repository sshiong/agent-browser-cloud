/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProviderEvidenceRequirement } from './ProviderEvidenceRequirement.js';
import type { RecoveryTargetIndicator } from './RecoveryTargetIndicator.js';
export type RecoveryContract = {
    contractId: string;
    applicationId: string;
    version: number;
    expectedOrigins: Array<string>;
    readyRoutePrefixes: Array<string>;
    loginRoutePrefixes: Array<string>;
    requiredTargets: Array<RecoveryTargetIndicator>;
    loginTargets: Array<RecoveryTargetIndicator>;
    permissionDeniedTargets: Array<RecoveryTargetIndicator>;
    accountMismatchTargets: Array<RecoveryTargetIndicator>;
    requiredExtensionIds: Array<string>;
    requiredProviderEvidence: Array<ProviderEvidenceRequirement>;
    requireDocumentComplete: boolean;
    minimumNetworkQuietMillis: number;
    transientBlockerTargets: Array<RecoveryTargetIndicator>;
    allowDepthLimited: boolean;
    recoveryAction: 'NONE' | 'RELOAD' | 'NAVIGATE_HOME' | 'REOPEN_KNOWN_ROUTE' | 'REFRESH_SESSION' | 'RESTART_EXTENSION';
    recoveryExtensionId?: string | null;
    maximumAutoRecovery: number;
    enabled: boolean;
    approvalState?: 'DRAFT' | 'REQUESTED' | 'APPROVED' | 'REJECTED';
    approvalId?: string | null;
    approvalRequestedBy?: string | null;
    approvedBy?: string | null;
    approvalRequestedAt?: string | null;
    approvalDecidedAt?: string | null;
    createdAt: string;
    updatedAt: string;
};
