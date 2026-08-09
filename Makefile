.PHONY: install install-desktop build build-desktop build-sdk-release test test-desktop test-application-adapter test-validation-worker lint lint-desktop fmt compose-up compose-down clean contracts contracts-check sdk-typescript-generate sdk-typescript-check sdk-multilang-generate sdk-multilang-check migrate migrate-info docker-build supply-chain-check test-integration test-real-url-agent test-postgres-outage test-object-storage test-coordinator-capacity test-browser-runtime-capacity test-browser-density-capacity test-kubernetes-operator test-kubernetes-e2e test-upgrade-compatibility test-e2e test-sdk ci

BUF ?= pnpm dlx @bufbuild/buf@1.50.0
CAPACITY_BUILD_ID ?= $(shell git rev-parse HEAD)
RUNTIME_CAPACITY_CYCLES ?= 500
BROWSER_DENSITY_CONCURRENCY ?= 4
REAL_CHROMIUM_PATH ?=

# Install workspace dependencies
install:
	pnpm --dir apps/web-console install --frozen-lockfile
	pnpm --dir sdks/typescript install --frozen-lockfile

# Install the independently locked Tauri CLI.
install-desktop:
	pnpm --dir apps/desktop install --frozen-lockfile

# Build all components
build:
	./gradlew -p apps/control-plane build
	cargo build --locked --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console build
	python3 -m py_compile apps/application-adapter/application_adapter.py
	python3 -m py_compile apps/validation-worker/validation_worker.py
	python3 -m py_compile apps/validation-worker/runtime_validation_runner.py

# Build the shared Web UI and native desktop binary without producing unsigned installers.
build-desktop:
	pnpm --dir apps/desktop build:unsigned

# Run all tests
test:
	./gradlew -p apps/control-plane test
	cargo test --locked --workspace --manifest-path apps/browser-node/Cargo.toml
	pnpm --dir apps/web-console test
	$(MAKE) test-application-adapter
	$(MAKE) test-validation-worker

# Verify the dependency-free, least-privilege Provider/Lease integration runtime.
test-application-adapter:
	python3 -m unittest discover -s apps/application-adapter -p 'test_*.py' -v

# Verify the isolated, leased and fenced Runtime Validation Worker runtime.
test-validation-worker:
	python3 -m unittest discover -s apps/validation-worker -p 'test_*.py' -v

# Run native desktop security-boundary unit tests.
test-desktop:
	cargo test --locked --manifest-path apps/desktop/src-tauri/Cargo.toml

# Code check
lint:
	./gradlew -p apps/control-plane check
	cargo fmt --all --check --manifest-path apps/browser-node/Cargo.toml
	cargo clippy --locked --workspace --all-targets --manifest-path apps/browser-node/Cargo.toml -- -D warnings
	pnpm --dir apps/web-console lint
	pnpm --dir apps/web-console format:check

# Validate native formatting and the least-privilege Tauri configuration.
lint-desktop:
	cargo fmt --all --check --manifest-path apps/desktop/src-tauri/Cargo.toml
	cargo check --locked --manifest-path apps/desktop/src-tauri/Cargo.toml

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

# Generate the dependency-free TypeScript Fetch client from the authoritative OpenAPI contract.
sdk-typescript-generate:
	pnpm --dir sdks/typescript run generate

# Regeneration must be byte-for-byte clean so SDK models and every operation cannot drift.
sdk-typescript-check: sdk-typescript-generate
	python3 tools/sdk/verify_typescript_sdk.py packages/contracts/openapi/session-api.yaml sdks/typescript/src/generated sdks/typescript/generated-manifest.json
	git diff --exit-code -- sdks/typescript/src/generated sdks/typescript/generated-manifest.json

# Generate dependency-light, full-operation Python/Go/Java clients and native schema models.
sdk-multilang-generate:
	mkdir -p build/sdk
	pnpm --package=@redocly/cli@1.34.0 dlx redocly bundle packages/contracts/openapi/session-api.yaml --output build/sdk/session-api.json
	python3 tools/sdk/generate_multilang_sdks.py build/sdk/session-api.json packages/contracts/openapi/session-api.yaml .

# All 166 operations, 224 public schemas and generated file hashes must remain exact.
sdk-multilang-check: sdk-multilang-generate
	python3 tools/sdk/verify_multilang_sdks.py build/sdk/session-api.json packages/contracts/openapi/session-api.yaml .
	git diff --exit-code -- sdks/python/browsercloud/generated_client.py sdks/python/browsercloud/generated_models.py sdks/go/browsercloud/generated sdks/java/src/main/java/io/browsercloud/sdk/generated sdks/generated-multilang-manifest.json

build-sdk-release: sdk-typescript-check sdk-multilang-check
	mkdir -p build/sdk-release
	pnpm --dir sdks/typescript pack --pack-destination ../../build/sdk-release
	python3 tools/sdk/build_multilang_release.py .

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
	docker build -f apps/application-adapter/Dockerfile -t application-adapter:latest apps/application-adapter
	docker build -f apps/validation-worker/Dockerfile -t validation-worker:latest apps/validation-worker

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

# Verify S3-compatible checkpoint commit ordering, bounded timeout, and retry safety
test-object-storage:
	./tests/failure-injection/object-storage-timeout.sh

# Generate a build-bound Stage A Coordinator capacity certificate
test-coordinator-capacity:
	./gradlew -p apps/control-plane coordinatorCapacityCertificate -PcapacityBuildId=$(CAPACITY_BUILD_ID)

# Generate a real-Chromium 500-cycle lifecycle and resource-leak certificate
test-browser-runtime-capacity:
	test -n "$(REAL_CHROMIUM_PATH)"
	cargo run --release --locked --manifest-path apps/browser-node/Cargo.toml \
		-p runtime-supervisor --bin runtime-capacity-certificate -- \
		--chromium "$(REAL_CHROMIUM_PATH)" \
		--cycles "$(RUNTIME_CAPACITY_CYCLES)" \
		--build-id "$(CAPACITY_BUILD_ID)" \
		--output apps/browser-node/target/capacity/runtime-capacity.json

# Generate a real-Chromium concurrent Browser Density certificate
test-browser-density-capacity:
	test -n "$(REAL_CHROMIUM_PATH)"
	cargo run --release --locked --manifest-path apps/browser-node/Cargo.toml \
		-p runtime-supervisor --bin runtime-capacity-certificate -- \
		--chromium "$(REAL_CHROMIUM_PATH)" \
		--cycles "$(RUNTIME_CAPACITY_CYCLES)" \
		--concurrency "$(BROWSER_DENSITY_CONCURRENCY)" \
		--build-id "$(CAPACITY_BUILD_ID)" \
		--output apps/browser-node/target/capacity/browser-density-capacity.json

# Run BrowserSession operator unit tests
test-kubernetes-operator:
	python3 -m unittest discover -s tools/browser-session-operator -p 'test_*.py'

# Run BrowserSession operator against an ephemeral Kind cluster
test-kubernetes-e2e:
	./tests/kubernetes/kind-operator-e2e.sh

# Enforce expand-only schema, protobuf field and N/N-1 rolling compatibility
test-upgrade-compatibility:
	python3 ./tests/upgrade/n-minus-one-gate.py

# Run E2E tests
test-e2e:
	./tests/e2e/run.sh

# Verify the dependency-free Python, TypeScript, Go and Java SDKs
test-sdk:
	PYTHONPATH=sdks/python python3 -m unittest discover -s sdks/python/tests
	pnpm --dir sdks/typescript test
	pnpm --dir sdks/typescript build
	node tools/sdk/verify_typescript_package.mjs sdks/typescript
	bash tests/sdk/typescript-package.sh
	cd sdks/go && go test ./...
	mkdir -p sdks/java/build/classes
	javac --release 17 -d sdks/java/build/classes $$(find sdks/java/src -name '*.java' -print)
	java -cp sdks/java/build/classes io.browsercloud.sdk.BrowserCloudClientTest
	java -cp sdks/java/build/classes io.browsercloud.sdk.generated.BrowserCloudGeneratedClientTest
	mkdir -p build/sdk-release
	pnpm --dir sdks/typescript pack --pack-destination ../../build/sdk-release
	python3 tools/sdk/build_multilang_release.py .

# Run all checks (CI)
ci: lint test contracts-check sdk-typescript-check sdk-multilang-check supply-chain-check test-kubernetes-operator test-upgrade-compatibility test-coordinator-capacity test-sdk
