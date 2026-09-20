# Agent Browser Cloud

[简体中文](README.md) | [English](README.en.md)

A browser infrastructure platform built around a controlled Chromium runtime.

> Phase 4 MVP, Phase 6 local capacity/N/N−1, and the core Phase 7 enterprise operations
> capabilities have repository-level acceptance evidence. However, the project has not passed
> the complete V16 production gates and must not yet process real customer data. Refer to the
> [progress overview](docs/08-进度追踪.md), the
> [remaining-work register](docs/progress/33-当前未实现清单.md), and CI for the current commit.
> The default Compose stack is a localhost development environment and must not be exposed through
> a public reverse proxy. Read the [security policy](SECURITY.md) first.

## Quick start

### Requirements

- Java 21+
- Rust 1.86+
- Node.js 20+
- pnpm 8+
- Docker 24+
- Docker Compose 2.20+

All local Docker gates on macOS must use OrbStack. Before running them, confirm that `orbctl status`
reports `Running`, `docker context show` reports `orbstack`, and Docker's operating system is
`OrbStack`. Do not substitute Docker Desktop or the `desktop-linux` context for repository-local
acceptance testing.

```bash
orbctl status
docker context show
docker info --format 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}'
```

Install the frontend dependencies before the first run:

```bash
make install
```

### One-command startup

```bash
# Clone the repository.
git clone https://github.com/sshiong/agent-browser-cloud.git
cd agent-browser-cloud

# Configure only an OpenAI Responses-compatible Base URL, key, and model.
# End the Base URL at /v1; the system appends /responses.
export LOCAL_AGENT_MODEL_ENDPOINT=https://your-model-provider.example/v1
export LOCAL_AGENT_MODEL_API_KEY=your-provider-key
export LOCAL_AGENT_MODEL_NAME=code

# All settings below are optional. Revision defaults to local-v1; aggregate aliases
# normally leave the response model unlocked.
export LOCAL_AGENT_MODEL_REVISION=local-v1
export LOCAL_AGENT_MODEL_RESPONSE_NAME=
export LOCAL_AGENT_MODEL_TIMEOUT_SECONDS=120
export LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS=512

# Start the local development stack and the real Agent, Reviewer, Outcome, and Vision workers.
make compose-up

# Verify the services.
curl http://localhost:8080/actuator/health
# Open http://localhost:3000 in a browser.
# The enterprise operations workspace is at http://localhost:3000/enterprise.
```

The Local Compose Reviewer, Outcome, and Vision workers accept OpenAI Responses-compatible HTTP(S)
Base URLs on any domain or IP. Enter a URL ending in `/v1`; the system calls `/v1/responses`
automatically. This supports OpenAI, third-party providers, and local aggregate gateways. Keys are
opaque credentials and are not tied to a vendor. To keep the key out of container environments,
preflight writes `LOCAL_AGENT_MODEL_API_KEY` to the Git-ignored `.local/agent-model-api-key` with
mode `0600`, and workers mount only that Secret file. `LOCAL_AGENT_MODEL_API_KEY_FILE` remains
available for an existing private file. HTTP transmits the key and request content without
transport encryption and should only be used on a trusted host or network; production workers
still require HTTPS and an explicit host allowlist.

`make compose-up` rejects missing, overly permissive, or malformed model configuration before it
starts. It then builds the stack and waits until all four workers have successfully reached their
authoritative long-poll queues. The model key is mounted from a restricted file and is never placed
in worker environment variables. No fixture is used to impersonate a real model. See
[deploy/docker/local-agent.env.example](deploy/docker/local-agent.env.example) for the complete
configuration template. If you only need PostgreSQL and Redis, run
`docker compose up -d postgres redis`.

The real-browser login and interactive challenge gates can be run independently:

```bash
# Real Chrome: valid password, incorrect-password handling, and false-success rejection.
# Every case uses an independent Profile.
make test-real-login-agent

# Run the same Outcome gate against the explicitly configured HTTP(S) Responses provider.
make test-real-login-agent-provider

# Cloudflare's official forced-interactive test sitekey, using a real headed-Chrome mouse click.
# This does not access a production site.
make test-turnstile-interactive
```

Like the default Compose stack, `test-real-login-agent-provider` fails closed without a private key
file. These tests never read or reuse Codex or browser-account credentials. The Turnstile gate only
verifies interaction with Cloudflare's official test widget; it does not claim to bypass production
CAPTCHA. Cross-origin iframes remain governed as Opaque Frames.

The default Compose stack is for local development only. For a personal single-host deployment,
use the separate [Personal Secure deployment](deploy/personal-secure/). It enforces OIDC, random
file-backed secrets, internal mTLS, controlled proxy egress, HTTPS object storage, and the complete
Agent/Reviewer/Outcome/Vision worker chain while binding only to loopback. It is not a public
reverse-proxy template. Use SSH local port forwarding or a reviewed private network for remote
access.

### Step-by-step startup

On macOS, complete the three OrbStack checks above before running the following Docker commands.

```bash
# 1. Start infrastructure.
docker compose up -d postgres redis

# 2. Apply database migrations.
make migrate

# 3. Start the Control Plane (direct networking is explicitly local-only).
PROXY_ALLOW_DIRECT=true ./gradlew -p apps/control-plane bootRun

# 4. Start the Storage Helper first.
mkdir -p /tmp/browsercloud-helpers
STORAGE_HELPER_SOCKET=/tmp/browsercloud-helpers/storage.sock \
PROFILE_STORAGE_ROOT=/tmp/browsercloud-profile-storage \
  cargo run --manifest-path apps/browser-node/Cargo.toml --bin storage-helper &

# 5. Start the Browser Node (direct networking is explicitly local-only).
ALLOW_DIRECT_NETWORK=true \
STORAGE_HELPER_SOCKET=/tmp/browsercloud-helpers/storage.sock \
PROFILE_STORAGE_ROOT=/tmp/browsercloud-profile-storage \
  cargo run --manifest-path apps/browser-node/Cargo.toml --bin node-agent

# 6. Start the Web Console.
cd apps/web-console && pnpm dev
```

## Repository structure

The module table is generated from Git-tracked files. After adding or deleting modules, stage the
files and run `make docs-generate`. CI runs `make docs-check` and rejects module-table drift or
broken local links in either README.

<!-- BEGIN GENERATED MODULES -->

| Directory | Git-tracked modules |
| --- | --- |
| `apps/` | [agent-worker](apps/agent-worker/), [application-adapter](apps/application-adapter/), [browser-node](apps/browser-node/), [control-plane](apps/control-plane/), [desktop](apps/desktop/), [gameday-worker](apps/gameday-worker/), [validation-worker](apps/validation-worker/), [web-console](apps/web-console/) |
| `packages/` | [contracts](packages/contracts/) |
| `sdks/` | [go](sdks/go/), [java](sdks/java/), [python](sdks/python/), [typescript](sdks/typescript/) |
| `database/` | [migrations](database/migrations/), [online-migrations](database/online-migrations/), [seeds](database/seeds/) |
| `deploy/` | [docker](deploy/docker/), [kubernetes](deploy/kubernetes/), [personal-secure](deploy/personal-secure/), [terraform](deploy/terraform/) |
| `tools/` | [browser-session-operator](tools/browser-session-operator/), [docs](tools/docs/), [local-dev](tools/local-dev/), [sdk](tools/sdk/), [supply-chain](tools/supply-chain/) |

<!-- END GENERATED MODULES -->

`docs/` contains architecture and verification evidence, `tests/` contains cross-component gates,
the `Makefile` is the unified verification entry point, and `docker-compose.yml` defines the local
development stack. The default Compose stack now runs Agent Executor, Reviewer, Outcome Verifier,
and Vision as four independent workers and fails closed without real model configuration. See
[Default Compose full Agent runtime closure](docs/progress/189-默认Compose完整Agent运行链闭环.md).

## Documentation

- [MVP requirements](docs/01-MVP需求说明.md)
- [Formal API and message contracts](docs/02-正式API消息契约.md)
- [Database design](docs/03-数据库详细设计.md)
- [Work breakdown and schedule](docs/04-任务拆分与排期.md)
- [Test plan and cases](docs/05-测试计划与用例.md)
- [Local development guide](docs/06-本地开发指南.md)
- [Engineering standards](docs/07-工程规范.md)
- [Progress tracking](docs/08-进度追踪.md)
- [Compliance audit and remediation log](docs/09-合规审计与整改记录.md)
- [Architecture and implementation outlines](docs/outline/)

## Development

```bash
# Build all components.
make build

# Run all tests.
make test

# Run linters.
make lint

# Format code.
make fmt

# Generate contracts.
make contracts

# Validate contracts and run real-process smoke gates.
make contracts-check
make test-integration
make test-e2e
make test-real-url-agent
make test-sdk

# Produce the 500-cycle real-Chromium lifecycle capacity certificate.
make test-browser-runtime-capacity \
  REAL_CHROMIUM_PATH="/absolute/path/to/chromium" \
  RUNTIME_CAPACITY_CYCLES=500

# Operator unit tests. Cluster E2E also requires Docker, kubectl, and Kind.
make test-kubernetes-operator
KIND_BIN=/path/to/kind make test-kubernetes-e2e
make test-upgrade-compatibility
```

## API documentation

After starting the Control Plane, open:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs

Local list/detail APIs also require the development tenant guard header:

```bash
curl -H 'X-Tenant-Id: tenant-local' \
  http://localhost:8080/api/v1/sessions
```

`X-Tenant-Id` only prevents accidental cross-tenant reads during local development; it is not
authentication. Production must enable Bearer authentication and derive the tenant from the
authenticated principal.

## Technology stack

| Layer | Technology |
| --- | --- |
| Control Plane | Java 21 + Spring Boot |
| Browser Node | Rust + Tokio |
| Web Console | React + TypeScript |
| Database | PostgreSQL 17 |
| Cache | Redis 7 |
| Internal protocol | Protobuf |
| External API | OpenAPI 3.1 |
