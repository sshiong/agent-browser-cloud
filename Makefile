.PHONY: install build test lint fmt compose-up compose-down clean contracts contracts-check migrate migrate-info docker-build supply-chain-check test-integration test-real-url-agent test-postgres-outage test-coordinator-capacity test-kubernetes-operator test-kubernetes-e2e test-e2e ci

BUF ?= pnpm dlx @bufbuild/buf@1.50.0
CAPACITY_BUILD_ID ?= $(shell git rev-parse HEAD)

# Install workspace dependencies
install:
	pnpm --dir apps/web-console install --frozen-lockfile

# Build all components
build:
	./gradlew -p apps/control-plane build
	cargo build --locked --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console build

# Run all tests
test:
	./gradlew -p apps/control-plane test
	cargo test --locked --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console test

# Code check
lint:
	./gradlew -p apps/control-plane check
	cargo fmt --all --check --manifest-path apps/browser-node/Cargo.toml
	cargo clippy --locked --workspace --all-targets --manifest-path apps/browser-node/Cargo.toml -- -D warnings
	pnpm --dir apps/web-console lint
	pnpm --dir apps/web-console format:check

# Format code
fmt:
	./gradlew -p apps/control-plane spotlessApply
	cargo fmt --all --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console format

# Start local services
compose-up:
	docker compose up -d --build

# Stop local services
compose-down:
	docker compose down

# Clean build artifacts
clean:
	./gradlew -p apps/control-plane clean
	cargo clean --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console clean

# Generate contracts
contracts:
	cd packages/contracts && $(BUF) lint
	cd packages/contracts && $(BUF) generate

# Validate source contracts without regenerating code
contracts-check:
	cd packages/contracts && $(BUF) lint
	pnpm --package=@redocly/cli@1.34.0 dlx redocly lint packages/contracts/openapi/session-api.yaml
	python3 -m json.tool packages/contracts/json-schema/error-envelope.json >/dev/null

# Run database migration
migrate:
	./gradlew -p apps/control-plane flywayMigrate

# Show migration status
migrate-info:
	./gradlew -p apps/control-plane flywayInfo

# Build Docker images
docker-build:
	docker build -f apps/control-plane/Dockerfile -t control-plane:latest .
	docker build -f apps/browser-node/Dockerfile -t browser-node:latest .
	docker build -f apps/web-console/Dockerfile -t web-console:latest .

# Validate production release bundle invariants
supply-chain-check:
	./tests/supply-chain/release_bundle_test.sh

# Run integration tests
test-integration:
	./tests/integration/smoke.sh

# Run authorized public URLs through the real Browser Node and Chrome
test-real-url-agent:
	./tests/compatibility/real-url-agent-matrix.sh

# Verify bounded 503 responses and durable recovery across a real PostgreSQL outage
test-postgres-outage:
	./tests/failure-injection/postgres-outage.sh

# Generate a build-bound Stage A Coordinator capacity certificate
test-coordinator-capacity:
	./gradlew -p apps/control-plane coordinatorCapacityCertificate -PcapacityBuildId=$(CAPACITY_BUILD_ID)

# Run BrowserSession operator unit tests
test-kubernetes-operator:
	python3 -m unittest discover -s tools/browser-session-operator -p 'test_*.py'

# Run BrowserSession operator against an ephemeral Kind cluster
test-kubernetes-e2e:
	./tests/kubernetes/kind-operator-e2e.sh

# Run E2E tests
test-e2e:
	./tests/e2e/run.sh

# Run all checks (CI)
ci: lint test contracts-check supply-chain-check test-kubernetes-operator test-coordinator-capacity
