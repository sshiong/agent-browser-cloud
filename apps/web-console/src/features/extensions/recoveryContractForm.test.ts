import { describe, expect, it } from 'vitest';
import {
  emptyRecoveryContractForm,
  isChromiumExtensionId,
  isValidExpectedOrigin,
  isValidRoutePrefix,
  parseContractLines,
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
      recoveryAction: 'RESTART_EXTENSION',
      recoveryExtensionId: 'jdgnleokimdbblcflcfcohbinohmmmlb',
      maximumAutoRecovery: 1,
    });
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
