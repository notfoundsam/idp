FRONTEND := docker compose run --rm frontend corepack pnpm
GRADLE := cd backend && ./gradlew

.PHONY: install up stop clean cleanup dns-mapping setup-ssl smoke jars build test test-backend test-frontend lint frontend-install

install: dns-mapping setup-ssl frontend-install jars

up: jars
	docker compose up -d --wait --wait-timeout 180
	@command -v open >/dev/null && open https://dashboard.idp.test || xdg-open https://dashboard.idp.test || true

stop:
	docker compose stop

clean:
	docker compose down -v

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
	$(FRONTEND) -r --filter '!e2e' test

lint: frontend-install
	$(GRADLE) ktlintCheck
	$(FRONTEND) -r --if-present lint

frontend-install:
	$(FRONTEND) install --frozen-lockfile
