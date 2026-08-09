.DEFAULT_GOAL := help
SHELL := /bin/bash

# Locate a Java 21 toolchain without requiring the developer to have one on PATH.
# An explicit JAVA_HOME in the environment always wins.
ifeq ($(origin JAVA_HOME), undefined)
JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null || ls -d $$HOME/.jdks/jdk-21*/Contents/Home $$HOME/.jdks/jdk-21* 2>/dev/null | head -1)
endif
export JAVA_HOME

GRADLE := ./gradlew
COMPOSE := docker compose

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

## ---- build ----------------------------------------------------------------

.PHONY: build
build: ## Compile, test, format-check, static-analyse and enforce the coverage gate
	$(GRADLE) build

.PHONY: test
test: ## Run the test suites only
	$(GRADLE) test

.PHONY: fmt
fmt: ## Apply the code formatter in place
	$(GRADLE) spotlessApply

.PHONY: clean
clean: ## Delete build outputs
	$(GRADLE) clean

# Scoped to the modules that actually expose an HTTP API. A repo-wide `test --tests` would fail
# on every module with no matching test, which is not the same thing as a failure.
OPENAPI_MODULES := :policy-service:test :rating-service:test

.PHONY: openapi
openapi: ## Regenerate the committed OpenAPI documents from the running controllers
	$(GRADLE) $(OPENAPI_MODULES) --tests '*OpenApiSpecificationTest*' -DupdateOpenApi=true
	@echo "Regenerated. Review the diff: a change clients would notice is a breaking API change."

## ---- local stack ----------------------------------------------------------

.PHONY: up
up: ## Start PostgreSQL and Kafka and block until both are serving
	$(COMPOSE) up -d --wait
	@echo
	@echo "PostgreSQL  localhost:5432   databases: aegis_policy, aegis_rating, aegis_claims"
	@echo "Kafka       localhost:29092  (containers use kafka:9092)"

.PHONY: down
down: ## Stop the stack, keeping data volumes
	$(COMPOSE) down

.PHONY: reset
reset: ## Stop the stack and delete its volumes, so the database init script re-runs
	$(COMPOSE) down --volumes

.PHONY: ps
ps: ## Show stack status
	$(COMPOSE) ps

.PHONY: logs
logs: ## Follow stack logs
	$(COMPOSE) logs -f

.PHONY: verify-stack
verify-stack: ## Prove the stack is usable: list the databases and the Kafka broker's topics
	@echo "--- databases ---"
	@$(COMPOSE) exec -T postgres psql -U postgres -tAc \
		"SELECT datname FROM pg_database WHERE datname LIKE 'aegis%' ORDER BY datname"
	@echo "--- kafka api ---"
	@$(COMPOSE) exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
		--bootstrap-server localhost:9092 | head -1
