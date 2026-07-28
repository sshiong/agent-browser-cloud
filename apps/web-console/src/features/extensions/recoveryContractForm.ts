import type {
  BusinessRecoveryAction,
  RecoveryContractView,
  RecoveryTargetIndicator,
  UpsertRecoveryContractRequest,
} from '@/types/session';

export interface RecoveryContractFormValues {
  applicationId: string;
  expectedOrigins: string;
  readyRoutePrefixes: string;
  loginRoutePrefixes: string;
  requiredTargets: RecoveryTargetIndicator[];
  loginTargets: RecoveryTargetIndicator[];
  permissionDeniedTargets: RecoveryTargetIndicator[];
  accountMismatchTargets: RecoveryTargetIndicator[];
  requiredExtensionIds: string;
  allowDepthLimited: boolean;
  recoveryAction: BusinessRecoveryAction;
  recoveryExtensionId: string;
  maximumAutoRecovery: number;
  enabled: boolean;
}

export const emptyRecoveryContractForm: RecoveryContractFormValues = {
  applicationId: '',
  expectedOrigins: '',
  readyRoutePrefixes: '',
  loginRoutePrefixes: '',
  requiredTargets: [],
  loginTargets: [],
  permissionDeniedTargets: [],
  accountMismatchTargets: [],
  requiredExtensionIds: '',
  allowDepthLimited: false,
  recoveryAction: 'NONE',
  recoveryExtensionId: '',
  maximumAutoRecovery: 0,
  enabled: true,
};

export function parseContractLines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((item) => item.trim()))]
    .filter(Boolean)
    .sort();
}

export function isValidExpectedOrigin(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      (url.protocol === 'http:' || url.protocol === 'https:') &&
      url.username === '' &&
      url.password === '' &&
      (url.pathname === '/' || url.pathname === '') &&
      url.search === '' &&
      url.hash === ''
    );
  } catch {
    return false;
  }
}

export function isValidRoutePrefix(value: string): boolean {
  return (
    value.startsWith('/') &&
    !value.includes('..') &&
    !value.includes('?') &&
    !value.includes('#')
  );
}

export function isChromiumExtensionId(value: string): boolean {
  return /^[a-p]{32}$/.test(value);
}

export function recoveryContractToForm(
  contract: RecoveryContractView
): RecoveryContractFormValues {
  return {
    applicationId: contract.applicationId,
    expectedOrigins: contract.expectedOrigins.join('\n'),
    readyRoutePrefixes: contract.readyRoutePrefixes.join('\n'),
    loginRoutePrefixes: contract.loginRoutePrefixes.join('\n'),
    requiredTargets: contract.requiredTargets,
    loginTargets: contract.loginTargets,
    permissionDeniedTargets: contract.permissionDeniedTargets,
    accountMismatchTargets: contract.accountMismatchTargets,
    requiredExtensionIds: contract.requiredExtensionIds.join('\n'),
    allowDepthLimited: contract.allowDepthLimited,
    recoveryAction: contract.recoveryAction,
    recoveryExtensionId: contract.recoveryExtensionId ?? '',
    maximumAutoRecovery: contract.maximumAutoRecovery,
    enabled: contract.enabled,
  };
}

export function recoveryContractRequest(
  values: RecoveryContractFormValues,
  expectedVersion: number
): UpsertRecoveryContractRequest {
  return {
    expectedVersion,
    expectedOrigins: parseContractLines(values.expectedOrigins),
    readyRoutePrefixes: parseContractLines(values.readyRoutePrefixes),
    loginRoutePrefixes: parseContractLines(values.loginRoutePrefixes),
    requiredTargets: normalizeTargets(values.requiredTargets),
    loginTargets: normalizeTargets(values.loginTargets),
    permissionDeniedTargets: normalizeTargets(values.permissionDeniedTargets),
    accountMismatchTargets: normalizeTargets(values.accountMismatchTargets),
    requiredExtensionIds: parseContractLines(values.requiredExtensionIds),
    allowDepthLimited: values.allowDepthLimited,
    recoveryAction: values.recoveryAction,
    recoveryExtensionId:
      values.recoveryAction === 'RESTART_EXTENSION'
        ? values.recoveryExtensionId.trim()
        : undefined,
    maximumAutoRecovery: values.maximumAutoRecovery,
    enabled: values.enabled,
  };
}

function normalizeTargets(
  targets: RecoveryTargetIndicator[]
): RecoveryTargetIndicator[] {
  return targets
    .map((target) => ({
      role: target.role.trim().toLowerCase(),
      name: target.name.trim(),
    }))
    .filter((target) => target.role && target.name)
    .sort(
      (left, right) =>
        left.role.localeCompare(right.role) ||
        left.name.localeCompare(right.name)
    );
}
