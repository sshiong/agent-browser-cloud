.PHONY: install build test lint fmt compose-up compose-down clean contracts contracts-check migrate migrate-info docker-build test-integration test-e2e ci

BUF ?= pnpm dlx @bufbuild/buf@1.50.0

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

# Run integration tests
test-integration:
	./tests/integration/smoke.sh

# Run E2E tests
test-e2e:
	pnpm --dir apps/web-console test:e2e

# Run all checks (CI)
ci: lint test contracts-check
