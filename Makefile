FRONTEND := docker compose run --rm frontend corepack pnpm
GRADLE := cd backend && ./gradlew
TERRAFORM := docker compose run --rm terraform
TERRAFORM_ROOTS := bootstrap environments/staging environments/production
TERRAFORM_TESTED_MODULES := modules/alarms modules/app modules/edge modules/iam modules/network modules/release modules/static modules/telemetry
TFLINT := docker compose run --rm tflint --config /repo/infra/terraform/.tflint.hcl

.PHONY: install up up-observability stop clean cleanup dns-mapping setup-ssl smoke jars build test test-backend test-frontend test-deploy test-infra test-e2e load lint lint-backend lint-frontend lint-infra frontend-install web-release deploy-install check-registries sbom vulnerabilities release-smoke smoke-sso

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

test: test-backend test-frontend test-deploy

test-backend:
	$(GRADLE) check

test-frontend: frontend-install
	$(FRONTEND) test
	$(FRONTEND) -r --filter '!e2e' test
	docker compose run --rm frontend node --test '/app/infra/web/*.test.mjs'

# The frontend of a release, built with its version into frontend/release for `release-assets.mjs stage`, as .github/workflows/deploy.yml does.
web-release: frontend-install
	$(if $(APP_VERSION),,$(error APP_VERSION is required))
	docker compose run --rm --no-deps -e APP_VERSION=$(APP_VERSION) -e WEB_OUT_DIR=/app/frontend/release frontend \
	  corepack pnpm -r --filter '!e2e' --if-present build

# The release scripts against fakes of S3, ECS and the IdP, in the image of the frontend with the packages of infra/deploy alone.
test-deploy: deploy-install
	docker compose run --rm --no-deps frontend node --test '/app/infra/deploy/*.test.mjs'

# infra/deploy is a pnpm project of its own, so the smoke test brings no package into the pages, see docs/release.md.
deploy-install:
	docker compose run --rm --no-deps -v "$(CURDIR)/infra/deploy:/deploy" -w /deploy frontend corepack pnpm install --frozen-lockfile

# Maven Central, the Gradle Plugin Portal, Shibboleth for OpenSAML and npm only, see docs/security/dependencies.md.
check-registries:
	docker compose run --rm --no-deps -v "$(CURDIR):/repo:ro" -w /repo frontend node infra/deploy/registries.mjs

# The CycloneDX SBOMs of what a release ships, into build/sbom: the runtime classpath of the jars and the production packages of the
# apps. Then Trivy matches them with known vulnerabilities, and the release stops on one past its time frame.
sbom:
	$(GRADLE) cyclonedxBom
	mkdir -p build/sbom
	cp backend/build/reports/cyclonedx/bom.json build/sbom/backend.cdx.json
	docker compose run --rm --no-deps -v "$(CURDIR)/build/sbom:/sbom" frontend corepack pnpm --filter './apps/*' sbom \
	  --sbom-format cyclonedx --sbom-type application --sbom-spec-version 1.6 --prod --lockfile-only --out '/sbom/%s.cdx.json'

vulnerabilities:
	for bom in build/sbom/*.cdx.json; do \
	  docker compose run --rm trivy sbom --quiet --format json --output "$${bom%.cdx.json}.trivy.json" "$$bom" || exit 1; \
	done
	docker compose run --rm --no-deps -v "$(CURDIR):/repo:ro" -w /repo frontend node infra/deploy/vulnerabilities.mjs \
	  $$(for bom in build/sbom/*.cdx.json; do printf -- '--sbom %s --report %s ' "$$bom" "$${bom%.cdx.json}.trivy.json"; done) \
	  --accepted infra/deploy/accepted-vulnerabilities.json

# The synthetic tenant of the release smoke test, set up once like in an environment, and the smoke test against it.
release-smoke: deploy-install
	dev-tools/release-smoke.sh

smoke-sso: deploy-install
	cp "$$(mkcert -CAROOT)/rootCA.pem" dev-tools/certs/rootCA.pem
	SMOKE_LOGIN="$$(cat dev-tools/release-smoke/login.json)" docker compose run --rm --no-deps release-smoke \
	  node infra/deploy/smoke-sso.mjs --host release-smoke.idp.test

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
	docker compose run --rm actionlint

frontend-install:
	$(FRONTEND) install --frozen-lockfile
