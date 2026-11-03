# Testing

How the project tests its code and with which tools. A change that adds a tool or a new kind of test updates this file.

## Layers

| Layer | What runs | Example |
|---|---|---|
| Domain | Plain Kotlin, no Spring and no database | `modules/identity/src/test/.../domain/UserTest.kt` |
| Application services | The service against a real Postgres, ports outside the database replaced by hand-written fakes | `IdentityServiceTest`, `SessionsTest` |
| HTTP | The real application on a random port, called over HTTP | `app/src/test/.../login/FlowApiTest.kt` |
| API contract | Controllers and responses checked against `contracts/openapi/*.yaml` | `FlowContractTest`, `AccountContractTest`, `AdminContractTest`, `OpsContractTest` |
| Telemetry | The real application with spans kept in memory | `app/src/test/.../web/TracingTest.kt` |
| SAML messages | Requests a test SP writes, signs and encodes, against the validator and over HTTP | `AuthnRequestValidatorTest`, `SsoApiTest`, `ResumeApiTest` |
| SAML login of `ops` | Responses a fake IdP writes and signs, each bent in one way, posted to the ACS of the real `ops` | `ops/src/test/.../security/OpsSamlLoginTest.kt` |
| SAML interoperability | SimpleSAMLphp in a container with the configuration of `dev-tools/simplesamlphp`, the login over the HTTP API | `app/src/test/.../saml/SamlInteropTest.kt` |
| Servlet filter | One filter called directly with servlet mocks, where a limit is too large to reach over HTTP | `SamlThrottleFilterTest` |
| Architecture | Module boundaries and the build graph | `architecture-tests` |
| KMS and erasure journal | The AWS adapters against LocalStack | `*LocalStackTest` in `shared-kernel` |
| Frontend | Unit tests, and the committed API types against a fresh generation from `contracts/openapi` | `frontend/packages/api-client/src/generated.test.ts` |
| Frontend components | A page mounted in happy-dom with a scripted API client, which answers each operation from a queue | `frontend/apps/login/src/App.test.ts`, `frontend/apps/console/src/console.test.ts` |
| Build of the console | Vite builds the console in memory, then again with app code changed and with another version, and the `vendor` chunk keeps its name | `frontend/apps/console/vendor.test.ts` |
| Lint rules | Each security rule of `frontend/eslint.config.js` against a snippet that breaks it | `frontend/lint/lint.test.ts` |
| End to end | Chromium against the environment of `make up`, through Traefik, nginx, `app` and SimpleSAMLphp, with the links of mails read from Mailpit and passkeys from a virtual authenticator | `frontend/e2e/login.spec.ts` |
| Login of `ops` | Chromium through the login of the service tenant into the real `ops`, with a passkey | `frontend/e2e/ops-login.spec.ts` |
| Console | Chromium through the console of an invited user: factors, sessions in two browser contexts, a fresh login, and a logout with the API cut off by Playwright routes | `frontend/e2e/account.spec.ts` |
| Releases | A page open during a deploy: the bundle of the previous release from `frontend/e2e/fixtures/previous-release`, versions and failures of the API set by Playwright routes | `frontend/e2e/release.spec.ts` |
| Static files | The nginx config rendered from `infra/web/security-headers.json`, and the policy in that file | `infra/web/render-nginx.test.mjs` |

## Tools

- **Kotest** with `FunSpec` (`StringSpec` in a few specs) and `kotest-assertions`, on the JUnit Platform.
- **Testcontainers** for Postgres, PgBouncer, OpenBao, Valkey, Mailpit, SimpleSAMLphp and LocalStack. Test images are read from `docker-compose.yml` in `backend/build.gradle.kts`, so tests run what the local environment runs.
- **ArchUnit** for architecture rules.
- **OpenTelemetry SDK testing** for `InMemorySpanExporter`.
- **spring-test** only for its servlet mocks, `MockHttpServletRequest` and `MockFilterChain`.
- **`TestSp`** in the `federation` test fixtures: an SP with its own key that writes an AuthnRequest by hand, so a test can bend any part of it, and signs or encodes it for either binding.
- **`FakeIdp`** and **`OpsClient`** in the tests of `ops`: the service tenant with a key of its own, whose metadata file `ops` is given, and a browser that names the host `ops.idp.test`, keeps cookies, echoes the CSRF cookie and logs in through the fake IdP. The host is a restricted header of the JDK client, so the test JVM sets `jdk.httpclient.allowRestrictedHeaders=host`.
- **Vitest** for the frontend, with **Vue Test Utils** and **happy-dom** for components.
- **ESLint** with `typescript-eslint` and `eslint-plugin-vue`, and `vue-tsc` for strict TypeScript. A new security rule gets a snippet that breaks it in `lint.test.ts`.
- **Playwright** in its own image, run by `make test-e2e` as the `e2e` service of compose. The image tag and `@playwright/test` have the same version, and Renovate raises them together. The tenant hosts point at Traefik on the host through `extra_hosts`. Node trusts the mkcert CA through `NODE_EXTRA_CA_CERTS`, and Chromium the key of the served certificate through `--ignore-certificate-errors-spki-list`, so pages are a secure context for passkeys. The CI job `e2e` sets up the runner as a laptop, with `make dns-mapping setup-ssl`, then runs `make up`, `make smoke` and `make test-e2e`, and keeps the report of a failed run.
- **Virtual authenticator** of Chromium through the WebAuthn domain of CDP, in `e2e/support/passkey.ts`: a platform authenticator with user verification that answers without a prompt. It belongs to the page, so a test that signs in again clears the cookies and keeps the page.
- **`node:test`** for the scripts in `infra/web`, so they need no dependencies. `make test-frontend` runs them in the `frontend` container.
- **oasdiff** in CI for breaking changes in the API contracts.

Not used: MockMvc, the Spring test context, Mockito and MockK.

## Rules

- **Failure cases.** Every change ships with tests for its failure cases, not only the happy path.
- **Names.** A test name is a sentence about behaviour, such as `"accepting an invitation conforms, also when refused"`.
- **No mocking library.** A port outside the database gets a small fake in the test, such as `RecordingAudit` or `RecordingKms`.
- **Real HTTP.** A spec starts the application with `SpringApplication(...).run("--server.port=0")` once and closes it in `afterSpec`. Requests go through `java.net.http.HttpClient`, so they pass the real Tomcat, filter chain, cookies and headers. See `LoginEnvironment`, `OpsEnvironment` and `WorkerEnvironment`.
- **Tenant by Host.** `app` tests set the `Host` header to choose a tenant. `app/build.gradle.kts` allows it with `jdk.httpclient.allowRestrictedHeaders=host`.
- **Isolation.** A tenant isolation test uses at least two tenants.
- **Time.** Code takes a `Clock`, and tests move it with `MovableClock`.
- **Architecture rules.** Each rule has a fixture with a violation in `architecture-tests/src/test/kotlin/archfixtures`, and a new table needs an entry in `tableOwners`.
- **Spans.** The services export nothing without an OTLP endpoint, so tests send nothing out. A test that reads spans passes a bean class with an `InMemorySpanExporter` as an extra source, sets `management.tracing.sampling.probability=1.0`, and flushes `SdkTracerProvider` before reading. The class has no `@Configuration`, or the component scan of every other spec in the module would pick it up.
- **Hostile XML.** A parser of XML from outside has negative tests for a DOCTYPE with external or nested entities, XInclude, a foreign schema and the size limit. See `MetadataImporterTest`.
- **End to end.** The specs share the fixtures of `acme`, so they run one at a time. A TOTP code counts once, and `enterTotp` waits for the next window when an earlier run spent the current one. A page that should work within the CSP collects its violations from the console and expects none. An invitation works once, so `make test-e2e` first runs `dev-tools/e2e-invitation.sh`: the operator CLI reinvites `invitee@acme.test`, `passkey@acme.test`, `account@acme.test`, `operator@ops-sso.test` and `outsider@ops-sso.test`, which leaves them without a password or factors, and older mails to them are deleted. That also brings back the invitation of the first operator after Mailpit is recreated. The tests of one user follow one another in a `describe.serial`. A test that signs in again and must not wait for a TOTP window spends a recovery code with `enterRecoveryCode`. The console sends a browser without a session to the login, so a test waits with `atConsole` until it shows the account before it clears the cookies. `linkFromMail` polls the Mailpit API for a mail newer than the action that sent it. Every request reaches `app` from the one address Traefik names, so compose raises the limits by caller with `THROTTLE_MULTIPLIER=100`, the same locally and in CI.
- **Releases.** `openedBeforeRelease` serves the bundle of the previous release from `e2e/fixtures/previous-release` and makes the API name its version, until the test deploys. The live release then answers, and the old files stay, as in S3. `deployWhileOpen` makes the API name a newer version until the page reloads. A test tells a reload from a change of step by the loads of the page. The local backend has no minimum client version, so a route answers the `426`. After a release, the fixture is rebuilt from its tag: `docker compose run --rm -e APP_VERSION=<version> frontend corepack pnpm --filter @idp/login exec vite build --outDir ../../e2e/fixtures/previous-release --emptyOutDir`, with `PREVIOUS_VERSION` in `e2e/support/release.ts` set to the same version.
- **API contract.** A contract test built on `OpenApiContract` fails when the controllers and the contract list different operations, so a new endpoint needs its contract first. Responses, refusals included, are checked against the contract's schemas.

## Database

`TestDatabase` in the `shared-kernel` test fixtures runs one Postgres and one PgBouncer per test JVM. Roles are cluster-wide, so each test gets its own database:

- `TestDatabase.migrated()` clones a database with the current migrations. Most tests start here, because a clone costs a tenth of migrating.
- `TestDatabase.create()` gives an empty database, for tests of the migrations.
- `tenantSchema()` hands out a migrated tenant schema that `platform.tenants` does not know yet. The test adds the row. Clones hand out the same tenant ids, so a test that keeps tenant data in OpenBao or Valkey names it on its own.
- `pooled(role)` connects through PgBouncer with the configuration from `dev-tools`, as the application does.
- A test that proves one transaction over the real HTTP path makes the last write fail with a trigger that raises, limited to the rows of its user, and drops the trigger in `finally`. The first write must then be gone. See `revokingFails` in `AdminUsersApiTest`.

## Commands

```bash
make test                                  # backend and frontend
cd backend && ./gradlew check              # backend tests, ktlint, architecture rules
cd backend && ./gradlew :app:test --tests 'idp.app.login.FlowApiTest'
cd backend && ./gradlew :shared-kernel:localStackTest
make test-frontend                         # runs in Docker, also the tests of infra/web and the lint rules
make lint-frontend                         # ESLint and the type check of every package
make test-e2e                              # Playwright against a running `make up`
docker compose run --rm frontend corepack pnpm --filter @idp/api-client generate   # after a contract change
```

`./gradlew test` leaves out `*LocalStackTest`, which runs as its own CI job.

An environment variable reaches the test JVM only with `--no-configuration-cache`. With the cache on, Gradle reuses the stored environment and the new value is silently ignored.
