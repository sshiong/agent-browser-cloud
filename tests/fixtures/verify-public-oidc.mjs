// Test-only OIDC verifier for the official Duende public client. No token material is printed.
import { createPublicKey, timingSafeEqual, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const requireValid = (valid) => {
  if (!valid) throw new Error('OIDC_PROOF_REJECTED');
};

export function verifyPublicOidc({ token, jwks, nonce, userinfo }, now = Date.now() / 1000) {
  requireValid(typeof token === 'string' && token.length <= 16384);
  const parts = token.split('.');
  requireValid(parts.length === 3 && parts.every(part => /^[A-Za-z0-9_-]+$/.test(part)));
  const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
  const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
  requireValid(header.alg === 'RS256' && typeof header.kid === 'string' && !header.crit);
  const keys = jwks.keys.filter(key => key.kid === header.kid && key.kty === 'RSA'
    && (!key.use || key.use === 'sig') && (!key.alg || key.alg === 'RS256')
    && (!key.key_ops || key.key_ops.includes('verify')) && !key.d);
  requireValid(keys.length === 1);
  const key = createPublicKey({ key: keys[0], format: 'jwk' });
  requireValid(verify('RSA-SHA256', Buffer.from(parts.slice(0, 2).join('.')), key,
    Buffer.from(parts[2], 'base64url')));
  requireValid(claims.iss === 'https://demo.duendesoftware.com');
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  requireValid(audiences.includes('interactive.public')
    && (audiences.length === 1 || claims.azp === 'interactive.public')
    && (claims.azp === undefined || claims.azp === 'interactive.public'));
  requireValid(Number.isInteger(claims.exp) && claims.exp > now
    && Number.isInteger(claims.iat) && claims.iat <= now + 60 && claims.iat >= now - 300
    && (claims.nbf === undefined || (Number.isInteger(claims.nbf) && claims.nbf <= now)));
  requireValid(typeof nonce === 'string' && nonce.length >= 32
    && typeof claims.nonce === 'string' && claims.nonce.length === nonce.length
    && timingSafeEqual(Buffer.from(claims.nonce), Buffer.from(nonce)));
  requireValid(typeof claims.sub === 'string' && claims.sub.length > 0
    && userinfo.sub === claims.sub && userinfo.name === 'Bob Smith');
  return { signature: true, issuerAudienceNonce: true, userinfoSubject: true };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const input = readFileSync(0);
    requireValid(input.length <= 65536);
    process.stdout.write(JSON.stringify(verifyPublicOidc(JSON.parse(input))));
  } catch {
    process.stderr.write('OIDC_PROOF_REJECTED\n');
    process.exitCode = 1;
  }
}
