FRONTEND := docker compose run --rm frontend corepack pnpm
GRADLE := cd backend && ./gradlew

.PHONY: build test test-backend test-frontend lint frontend-install

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
