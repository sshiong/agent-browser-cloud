import { generateKeyPairSync, sign } from 'node:crypto';
import assert from 'node:assert/strict';
import test from 'node:test';
import { verifyPublicOidc } from '../fixtures/verify-public-oidc.mjs';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const nonce = 'random-test-nonce-with-at-least-32-characters';
const jwks = { keys: [{ ...publicKey.export({ format: 'jwk' }), kid: 'test-key', use: 'sig' }] };
const claims = { iss: 'https://demo.duendesoftware.com', aud: 'interactive.public',
  sub: '2', nonce, iat: 1000, exp: 1200 };
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
function input(overrides = {}, header = {}) {
  const content = `${encode({ alg: 'RS256', kid: 'test-key', ...header })}.${encode({ ...claims, ...overrides })}`;
  return { token: `${content}.${sign('RSA-SHA256', Buffer.from(content), privateKey).toString('base64url')}`,
    jwks, nonce, userinfo: { sub: '2', name: 'Bob Smith' } };
}

test('verifies a signed public-client ID token and matching UserInfo', () => {
  assert.deepEqual(verifyPublicOidc(input(), 1100),
    { signature: true, issuerAudienceNonce: true, userinfoSubject: true });
});
for (const [reason, overrides] of Object.entries({
  issuer: { iss: 'https://attacker.invalid' }, audience: { aud: 'another-client' },
  nonce: { nonce: 'incorrect-test-nonce-with-at-least-32-characters' },
  expiration: { exp: 1001 }, future: { iat: 1300 }, stale: { iat: 700 },
  authorizedParty: { aud: ['interactive.public', 'another-client'], azp: 'another-client' },
  notBefore: { nbf: 1150 },
})) {
  test(`rejects an otherwise signed token with invalid ${reason}`, () => {
    assert.throws(() => verifyPublicOidc(input(overrides), 1100));
  });
}
test('rejects signature substitution, algorithm downgrade and mismatched UserInfo', () => {
  const tampered = input();
  const parts = tampered.token.split('.');
  parts[1] = encode({ ...claims, sub: '1' });
  assert.throws(() => verifyPublicOidc({ ...tampered, token: parts.join('.') }, 1100));
  assert.throws(() => verifyPublicOidc(input({}, { alg: 'none' }), 1100));
  assert.throws(() => verifyPublicOidc({ ...input(), userinfo: { sub: '1', name: 'Bob Smith' } }, 1100));
  assert.throws(() => verifyPublicOidc({ ...input(), jwks: { keys: [...jwks.keys, ...jwks.keys] } }, 1100));
});
