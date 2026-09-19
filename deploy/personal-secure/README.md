# Personal Secure deployment

This layer is the supported single-host personal deployment profile. It is deliberately separate
from the default developer Compose file and does not turn Local Mode into a public service.

It provides:

- non-local Control Plane mode with real OIDC/JWT authentication;
- generated independent database, Redis, ticket, capability, payload, audit and Profile archive
  keys;
- internal Control Plane ↔ Browser Node mutual TLS and HTTPS Worker ingress;
- separate `AGENT_WORKER`, `REVIEWER_WORKER`, `OUTCOME_VERIFIER_WORKER` and `VISION_WORKER`
  identities and processes;
- an isolated Network Helper path through a dedicated Squid egress hop, with Browser Node attached
  only to the internal network;
- mandatory HTTPS object storage and encrypted Profile checkpoint archives;
- read-only, capability-dropped application containers and only one loopback host port.

It remains a personal/single-host profile, not a V16 production certification. Multi-region,
managed KMS/HSM, enterprise IdP lifecycle, external secret rotation, host full-disk encryption,
backup restore and organizational release gates remain separate production requirements.

## Prerequisites

- Docker Engine/Compose, OpenSSL and Python 3;
- an HTTPS OIDC issuer, a dedicated API audience, and a public PKCE client allowing
  `http://127.0.0.1:3000/auth/callback` (adjust the port consistently when needed);
- four JWT files issued by that issuer, each with exactly the required least-privilege role:
  `AGENT_WORKER`, `REVIEWER_WORKER`, `OUTCOME_VERIFIER_WORKER`, or `VISION_WORKER`;
- an HTTPS S3-compatible bucket, a model API key, and the expected public IP of the host's egress.

Worker JWTs must expose the configured roles claim as a string array and remain valid for at least
one hour at startup. The bootstrap validates shape, role and expiry; the Control Plane validates
issuer and signature. Rotate expiring files through the IdP and rerun `init`, then recreate the
affected workers. This profile never manufactures a privileged JWT or stores external credentials
in the Compose environment.

## Start

```bash
cp deploy/personal-secure/.env.example deploy/personal-secure/.env
# Edit only non-secret settings and absolute paths to private source files.

make personal-secure-init
make personal-secure-check
make personal-secure-up
```

Open `http://127.0.0.1:3000`. For access from another machine, use an authenticated SSH local port
forward or a reviewed private network that preserves loopback access. Do not publish the port, add
a public reverse proxy, or expose the Docker internal network. A public deployment requires the
full target-environment gates rather than this profile.

The generated `.env` and `.state/` are ignored by Git. `secret-init` copies source material into
service-specific named volumes before any application starts, so application containers receive
file paths rather than secret values in Compose metadata. Keep the host and Docker volume storage
encrypted and backed up according to the sensitivity of Profile/Cookie data.

## Operations

```bash
make personal-secure-check
./deploy/personal-secure/personal-secure status
make personal-secure-down
```

`check` is fail-closed: it validates HTTPS endpoints, exact model host, exit IP, JWT roles/expiry,
live OIDC discovery and the fully rendered Compose model. Set `PERSONAL_SECURE_OFFLINE_CHECK=1`
only in repository tests; do not use it for deployment.

Changing an external secret requires updating its source file, rerunning `init`, and recreating the
relevant services. `down` preserves named volumes. Destructive data-volume removal is intentionally
not wrapped by this script.
