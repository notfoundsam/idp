FRONTEND := docker compose run --rm frontend corepack pnpm
GRADLE := cd backend && ./gradlew
TERRAFORM := docker compose run --rm terraform
TERRAFORM_ROOTS := bootstrap environments/staging environments/production
TERRAFORM_TESTED_MODULES := modules/app modules/edge modules/iam modules/network modules/static
TFLINT := docker compose run --rm tflint --config /repo/infra/terraform/.tflint.hcl

.PHONY: install up up-observability stop clean cleanup dns-mapping setup-ssl smoke jars build test test-backend test-frontend test-infra test-e2e load lint lint-backend lint-frontend lint-infra frontend-install

install: dns-mapping setup-ssl frontend-install jars

# Docker on Linux would create the missing mount of SimpleSAMLphp as root, and fetch-idp-metadata.sh could not write there.
up: jars
	mkdir -p dev-tools/simplesamlphp/metadata
	docker compose up -d --wait --wait-timeout 180
	@command -v open >/dev/null && open https://dashboard.idp.test || xdg-open https://dashboard.idp.test || true

up-observability: export COMPOSE_PROFILES = observability
up-observability: export OTEL_EXPORTER_OTLP_ENDPOINT = http://lgtm:4318
up-observability: export OTEL_METRICS_EXPORTER = otlp
up-observability: up

stop:
	docker compose --profile '*' stop

clean:
	docker compose --profile '*' down -v

cleanup:
	./dev-tools/cleanup.sh

dns-mapping:
	./dev-tools/dns-mapping.sh

setup-ssl:
	./dev-tools/ssl-setup.sh

smoke:
	./dev-tools/smoke.sh

jars:
	$(GRADLE) bootJar

build: frontend-install
	$(GRADLE) assemble
	$(FRONTEND) -r --if-present build

test: test-backend test-frontend

test-backend:
	$(GRADLE) check

test-frontend: frontend-install
	$(FRONTEND) test
	$(FRONTEND) -r --filter '!e2e' test
	docker compose run --rm frontend node --test '/app/infra/web/*.test.mjs'

# terraform test against a mocked AWS provider: plans only, reads no state and calls no AWS API.
test-infra:
	for module in $(TERRAFORM_TESTED_MODULES); do \
	  $(TERRAFORM) -chdir=$$module init -backend=false -input=false -lockfile=readonly >/dev/null && \
	  $(TERRAFORM) -chdir=$$module test || exit 1; \
	done

# Runs against the environment of `make up`. SimpleSAMLphp needs the metadata of acme, Node in the container the mkcert CA, and the
# tests fresh invitations. CI runs the same target.
test-e2e: frontend-install
	dev-tools/simplesamlphp/fetch-idp-metadata.sh
	cp "$$(mkcert -CAROOT)/rootCA.pem" dev-tools/certs/rootCA.pem
	dev-tools/e2e-invitation.sh
	docker compose run --rm e2e

# Runs one scenario of dev-tools/k6 against `make up-observability`, for example `make load SCENARIO=saml DURATION=1m`. k6 trusts the
# mkcert CA, as Node does in test-e2e.
SCENARIO ?= login
load:
	cp "$$(mkcert -CAROOT)/rootCA.pem" dev-tools/certs/rootCA.pem
	docker compose run --rm -e DURATION -e RATE k6 run -o opentelemetry --tag testid=$(SCENARIO) $(SCENARIO).js

lint: lint-backend lint-frontend lint-infra

lint-backend:
	$(GRADLE) ktlintCheck

lint-frontend: frontend-install
	$(FRONTEND) lint
	$(FRONTEND) -r --if-present typecheck

# Offline checks only: the roots initialise without their S3 backend, so nothing here reads state or talks to AWS.
lint-infra:
	$(TERRAFORM) fmt -check -recursive
	for root in $(TERRAFORM_ROOTS); do \
	  $(TERRAFORM) -chdir=$$root init -backend=false -input=false -lockfile=readonly >/dev/null && \
	  $(TERRAFORM) -chdir=$$root validate || exit 1; \
	done
	$(TFLINT) --init
	$(TFLINT) --recursive

frontend-install:
	$(FRONTEND) install --frozen-lockfile
