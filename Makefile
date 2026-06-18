.PHONY: build run stop \
        test test-java test-python test-node test-go \
        compat-docker clean

MVN            = ./mvnw
PORT           = 4588
PID_FILE       = emulator.pid
JAVA_DIR       = compatibility-tests/sdk-test-java
PYTHON_DIR     = compatibility-tests/sdk-test-python
NODE_DIR       = compatibility-tests/sdk-test-node
GO_DIR         = compatibility-tests/sdk-test-go

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(MVN) compile

# ── Emulator: start / stop ────────────────────────────────────────────────────

run:
	$(MVN) quarkus:dev -Dno-color > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@echo "Waiting for emulator to start on port $(PORT)..."
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up!"

stop:
	@if [ -f $(PID_FILE) ]; then \
		kill $$(cat $(PID_FILE)) 2>/dev/null || true; \
		rm $(PID_FILE); \
	fi
	@kill $$(lsof -ti :$(PORT) -P 2>/dev/null) 2>/dev/null || true
	@until ! lsof -ti :$(PORT) -P > /dev/null 2>&1; do sleep 1; done
	@echo "Emulator stopped."

# ── SDK compatibility tests ───────────────────────────────────────────────────

test-java:
	@echo "==> Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -q

test-python:
	@echo "==> Python SDK compatibility tests"
	@cd $(PYTHON_DIR) && \
	if [ ! -d .venv ]; then python3 -m venv .venv; fi && \
	.venv/bin/pip install -q -r requirements.txt && \
	.venv/bin/pytest tests/ -v

test-node:
	@echo "==> Node.js SDK compatibility tests"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	npm test

test-go:
	@echo "==> Go SDK compatibility tests"
	@cd $(GO_DIR) && \
	go test ./tests/... -v -timeout 120s

# ── Full test suite ───────────────────────────────────────────────────────────

test: build
	$(MVN) test
	$(MAKE) run
	$(MAKE) test-java
	$(MAKE) test-python
	$(MAKE) test-node
	$(MAKE) test-go
	$(MAKE) stop

# ── Docker-based compat tests (requires: docker compose up -d) ────────────────
# Mirrors .github/workflows/compatibility.yml: each suite runs as its own
# container on the emulator's compose network. Per-service *_EMULATOR_HOST values
# are baked into each suite Dockerfile, so only the shared endpoint vars are
# passed here — new services stay in sync automatically via their Dockerfile.

COMPAT_NET     = floci_gcp_default
COMPAT_RESULTS = /tmp/floci-gcp-compat-results
COMPAT_SUITES  = sdk-test-java sdk-test-node sdk-test-python sdk-test-go compat-terraform compat-opentofu

compat-docker:
	@echo "==> Building test images..."
	@for suite in $(COMPAT_SUITES); do \
		echo "    compat-$$suite"; \
		docker build -q -t compat-$$suite compatibility-tests/$$suite/ > /dev/null; \
	done
	@fail=0; \
	for suite in $(COMPAT_SUITES); do \
		echo "==> $$suite"; \
		mkdir -p $(COMPAT_RESULTS)/$$suite; \
		docker run --rm --network $(COMPAT_NET) \
			-e FLOCI_GCP_ENDPOINT=http://floci-gcp:4588 \
			-e FLOCI_ENDPOINT=http://floci-gcp:4588 \
			-e FLOCI_HOST=floci-gcp:4588 \
			-e FLOCI_PROJECT=test-project \
			-v $(COMPAT_RESULTS)/$$suite:/results \
			compat-$$suite || fail=1; \
	done; \
	exit $$fail

# ── Cleanup ───────────────────────────────────────────────────────────────────

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/.venv $(PYTHON_DIR)/__pycache__ $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -f emulator.log $(PID_FILE)
