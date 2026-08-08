/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ProviderEvidenceRequirement } from './ProviderEvidenceRequirement.js';
import type { RecoveryTargetIndicator } from './RecoveryTargetIndicator.js';
export type UpsertRecoveryContractRequest = {
    expectedVersion: number;
    expectedOrigins: Array<string>;
    readyRoutePrefixes: Array<string>;
    loginRoutePrefixes: Array<string>;
    requiredTargets: Array<RecoveryTargetIndicator>;
    loginTargets: Array<RecoveryTargetIndicator>;
    permissionDeniedTargets: Array<RecoveryTargetIndicator>;
    accountMismatchTargets: Array<RecoveryTargetIndicator>;
    requiredExtensionIds: Array<string>;
    requiredProviderEvidence?: Array<ProviderEvidenceRequirement>;
    /**
     * Require document.readyState to equal complete before the application is Ready.
     */
    requireDocumentComplete?: boolean;
    /**
     * Required continuously observed network quiet window; zero disables this rule.
     */
    minimumNetworkQuietMillis?: number;
    /**
     * Exact accessible role and name indicators for transient Toast or Dialog blockers.
     */
    transientBlockerTargets?: Array<RecoveryTargetIndicator>;
    allowDepthLimited: boolean;
    recoveryAction?: 'NONE' | 'RELOAD' | 'NAVIGATE_HOME' | 'REOPEN_KNOWN_ROUTE' | 'REFRESH_SESSION' | 'RESTART_EXTENSION';
    /**
     * Required only for RESTART_EXTENSION and must also appear in requiredExtensionIds.
     */
    recoveryExtensionId?: string | null;
    maximumAutoRecovery: number;
    enabled: boolean;
};
