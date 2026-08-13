import { describe, expect, it } from 'vitest';
import {
  emptyRecoveryContractForm,
  isChromiumExtensionId,
  isValidExpectedOrigin,
  isValidRoutePrefix,
  parseContractLines,
  parseProviderEvidenceRequirements,
  recoveryContractRequest,
} from './recoveryContractForm';

describe('recovery contract form', () => {
  it('normalizes bounded declarative contract fields', () => {
    expect(parseContractLines(' /workspace\n/sign-in\n/workspace\n')).toEqual([
      '/sign-in',
      '/workspace',
    ]);

    const request = recoveryContractRequest(
      {
        ...emptyRecoveryContractForm,
        applicationId: 'crm.singapore',
        expectedOrigins: 'https://crm.example.test\nhttps://crm.example.test',
        readyRoutePrefixes: '/workspace',
        requiredTargets: [{ role: ' Status ', name: ' Recovered workspace ' }],
        requiredExtensionIds:
          'jdgnleokimdbblcflcfcohbinohmmmlb\njdgnleokimdbblcflcfcohbinohmmmlb',
        requiredProviderEvidence: `ACCOUNT | current-account | crm-provider | ${'a'.repeat(64)} | 300`,
        requireDocumentComplete: true,
        minimumNetworkQuietMillis: 1_500,
        transientBlockerTargets: [
          { role: ' Dialog ', name: ' Confirm payment ' },
        ],
        paymentSecurityRoutePrefixes: '/API/V2/Authorize\n/api/v2/authorize',
        criticalTransactionRoutePrefixes: '/Cases/Finalize',
        recoveryAction: 'RESTART_EXTENSION',
        recoveryExtensionId: 'jdgnleokimdbblcflcfcohbinohmmmlb',
        maximumAutoRecovery: 1,
      },
      7
    );

    expect(request).toMatchObject({
      expectedVersion: 7,
      expectedOrigins: ['https://crm.example.test'],
      readyRoutePrefixes: ['/workspace'],
      requiredTargets: [{ role: 'status', name: 'Recovered workspace' }],
      requiredExtensionIds: ['jdgnleokimdbblcflcfcohbinohmmmlb'],
      requiredProviderEvidence: [
        {
          type: 'ACCOUNT',
          key: 'current-account',
          providerId: 'crm-provider',
          expectedValueHash: 'a'.repeat(64),
          maxAgeSeconds: 300,
        },
      ],
      requireDocumentComplete: true,
      minimumNetworkQuietMillis: 1_500,
      transientBlockerTargets: [{ role: 'dialog', name: 'Confirm payment' }],
      paymentSecurityRoutePrefixes: ['/api/v2/authorize'],
      criticalTransactionRoutePrefixes: ['/cases/finalize'],
      recoveryAction: 'RESTART_EXTENSION',
      recoveryExtensionId: 'jdgnleokimdbblcflcfcohbinohmmmlb',
      maximumAutoRecovery: 1,
    });
  });

  it('rejects unbounded or unhashed Provider evidence requirements', () => {
    expect(
      parseProviderEvidenceRequirements(
        `PERMISSION | admin-scope | crm-provider | ${'b'.repeat(64)} | 120`
      )
    ).toEqual([
      {
        type: 'PERMISSION',
        key: 'admin-scope',
        providerId: 'crm-provider',
        expectedValueHash: 'b'.repeat(64),
        maxAgeSeconds: 120,
      },
    ]);
    expect(() =>
      parseProviderEvidenceRequirements(
        'ACCOUNT | current | crm-provider | raw-account-id | 300'
      )
    ).toThrow('PROVIDER_EVIDENCE_REQUIREMENT_INVALID');
  });

  it('uses the same origin, route and Chromium ID boundaries as the API', () => {
    expect(isValidExpectedOrigin('https://crm.example.test')).toBe(true);
    expect(isValidExpectedOrigin('https://user@crm.example.test')).toBe(false);
    expect(isValidExpectedOrigin('https://crm.example.test/path')).toBe(false);
    expect(isValidRoutePrefix('/workspace')).toBe(true);
    expect(isValidRoutePrefix('/workspace?tab=1')).toBe(false);
    expect(isChromiumExtensionId('jdgnleokimdbblcflcfcohbinohmmmlb')).toBe(
      true
    );
    expect(isChromiumExtensionId('unknown.integration')).toBe(false);
  });
});
