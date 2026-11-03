# Testing

How the project tests its code and with which tools. A change that adds a tool or a new kind of test updates this file.

## Layers

| Layer | What runs | Example |
|---|---|---|
| Domain | Plain Kotlin, no Spring and no database | `modules/identity/src/test/.../domain/UserTest.kt` |
| Application services | The service against a real Postgres, ports outside the database replaced by hand-written fakes | `IdentityServiceTest`, `SessionsTest` |
| HTTP | The real application on a random port, called over HTTP | `app/src/test/.../login/FlowApiTest.kt` |
| API contract | Controllers and responses checked against `contracts/openapi/*.yaml` | `FlowContractTest`, `OpsContractTest` |
| Telemetry | The real application with spans kept in memory | `app/src/test/.../web/TracingTest.kt` |
| SAML messages | Requests a test SP writes, signs and encodes, against the validator and over HTTP | `AuthnRequestValidatorTest`, `SsoApiTest`, `ResumeApiTest` |
| SAML login of `ops` | Responses a fake IdP writes and signs, each bent in one way, posted to the ACS of the real `ops` | `ops/src/test/.../security/OpsSamlLoginTest.kt` |
| SAML interoperability | SimpleSAMLphp in a container with the configuration of `dev-tools/simplesamlphp`, the login over the HTTP API | `app/src/test/.../saml/SamlInteropTest.kt` |
| Servlet filter | One filter called directly with servlet mocks, where a limit is too large to reach over HTTP | `SamlThrottleFilterTest` |
| Architecture | Module boundaries and the build graph | `architecture-tests` |
| KMS and erasure journal | The AWS adapters against LocalStack | `*LocalStackTest` in `shared-kernel` |
| Frontend | Unit tests, and the committed API types against a fresh generation from `contracts/openapi` | `frontend/packages/api-client/src/generated.test.ts` |
| Static files | The nginx config rendered from `infra/web/security-headers.json`, and the policy in that file | `infra/web/render-nginx.test.mjs` |

## Tools

- **Kotest** with `FunSpec` (`StringSpec` in a few specs) and `kotest-assertions`, on the JUnit Platform.
- **Testcontainers** for Postgres, PgBouncer, OpenBao, Valkey, Mailpit, SimpleSAMLphp and LocalStack. Test images are read from `docker-compose.yml` in `backend/build.gradle.kts`, so tests run what the local environment runs.
- **ArchUnit** for architecture rules.
- **OpenTelemetry SDK testing** for `InMemorySpanExporter`.
- **spring-test** only for its servlet mocks, `MockHttpServletRequest` and `MockFilterChain`.
- **`TestSp`** in the `federation` test fixtures: an SP with its own key that writes an AuthnRequest by hand, so a test can bend any part of it, and signs or encodes it for either binding.
- **`FakeIdp`** and **`OpsClient`** in the tests of `ops`: the service tenant with a key of its own, whose metadata file `ops` is given, and a browser that names the host `ops.idp.test`, keeps cookies, echoes the CSRF cookie and logs in through the fake IdP. The host is a restricted header of the JDK client, so the test JVM sets `jdk.httpclient.allowRestrictedHeaders=host`.
- **Vitest** for the frontend.
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
- **API contract.** A contract test built on `OpenApiContract` fails when the controllers and the contract list different operations, so a new endpoint needs its contract first. Responses, refusals included, are checked against the contract's schemas.

## Database

`TestDatabase` in the `shared-kernel` test fixtures runs one Postgres and one PgBouncer per test JVM. Roles are cluster-wide, so each test gets its own database:

- `TestDatabase.migrated()` clones a database with the current migrations. Most tests start here, because a clone costs a tenth of migrating.
- `TestDatabase.create()` gives an empty database, for tests of the migrations.
- `tenantSchema()` hands out a migrated tenant schema that `platform.tenants` does not know yet. The test adds the row. Clones hand out the same tenant ids, so a test that keeps tenant data in OpenBao or Valkey names it on its own.
- `pooled(role)` connects through PgBouncer with the configuration from `dev-tools`, as the application does.

## Commands

```bash
make test                                  # backend and frontend
cd backend && ./gradlew check              # backend tests, ktlint, architecture rules
cd backend && ./gradlew :app:test --tests 'idp.app.login.FlowApiTest'
cd backend && ./gradlew :shared-kernel:localStackTest
make test-frontend                         # runs in Docker, also the tests of infra/web
docker compose run --rm frontend corepack pnpm --filter @idp/api-client generate   # after a contract change
```

`./gradlew test` leaves out `*LocalStackTest`, which runs as its own CI job.

An environment variable reaches the test JVM only with `--no-configuration-cache`. With the cache on, Gradle reuses the stored environment and the new value is silently ignored.
