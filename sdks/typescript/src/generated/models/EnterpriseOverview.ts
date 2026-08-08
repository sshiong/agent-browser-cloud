/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ComplianceSnapshot } from './ComplianceSnapshot.js';
import type { CostRate } from './CostRate.js';
import type { EnterpriseRegion } from './EnterpriseRegion.js';
import type { ErrorBudget } from './ErrorBudget.js';
import type { LicenseInventory } from './LicenseInventory.js';
import type { MediaQuota } from './MediaQuota.js';
import type { RecoveryGameDay } from './RecoveryGameDay.js';
import type { RetentionPolicy } from './RetentionPolicy.js';
import type { RuntimeValidation } from './RuntimeValidation.js';
import type { SlaExclusion } from './SlaExclusion.js';
export type EnterpriseOverview = {
    validations: Array<RuntimeValidation>;
    costRates: Array<CostRate>;
    mediaQuota: (MediaQuota | null);
    errorBudget: (ErrorBudget | null);
    slaExclusions: Array<SlaExclusion>;
    retentionPolicies: Array<RetentionPolicy>;
    licenseInventory: Array<LicenseInventory>;
    regions: Array<EnterpriseRegion>;
    recoveryGameDays: Array<RecoveryGameDay>;
    latestCompliance: (ComplianceSnapshot | null);
    generatedAt: string;
};
