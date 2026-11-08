# Local environment

The host needs Docker, also for compiling the backend, a JVM to run Gradle, and [mkcert](https://github.com/FiloSottile/mkcert). Node is not needed: the frontend runs in Docker.

```bash
make install   # /etc/hosts entries (asks for sudo), certificates, dependencies, jars
make up        # builds the jars, starts everything, opens the dashboard
make up-observability  # the same with Grafana LGTM, see Observability
make smoke     # checks every service
make stop      # stops containers and keeps data
make clean     # removes containers and data
make cleanup   # removes /etc/hosts entries and certificates
```

After a backend change run `make jars`, then `docker compose restart app`.

## Services

| URL | Service |
|---|---|
| https://dashboard.idp.test | Homer, links to everything |
| https://mail.idp.test | Mailpit |
| https://sp.idp.test/simplesaml/ | SimpleSAMLphp, test Service Provider (`admin` / `admin`) |
| https://ops.idp.test | `ops` |
| https://acme.idp.test, https://globex.idp.test | Tenants, `/api` and `/saml` go to `app`, `/login/` and `/console/` to `nginx` |
| https://ops-sso.idp.test | Service tenant, the IdP of `ops`, routed to `app` like any tenant |
| http://localhost:3000 | Grafana, only after `make up-observability` |

Debug ports: `app` 5005, `worker` 5006, `ops` 5007. Postgres listens on `127.0.0.1:5432`.

## Frontend

`frontend` installs the dependencies and runs `vite build --watch` for every app into the `idp-web` volume, so a change is served after a reload of the page. `nginx` serves that volume as S3 behind CloudFront would: `/` leads to `/console/`, a path of an app without a file gets its `index.html`, files under `assets/` are immutable and a missing one is a `404`. Old chunks stay in the volume, as in S3 after a release.

The headers of the static files live in `infra/web/security-headers.json`, and `nginx-config` renders them into the nginx config on every `up`. After changing either file run `docker compose up -d --force-recreate nginx-config nginx`.

In AWS the CloudFront Function `infra/web/spa-rewrite.js` does what `nginx.conf.template` does. Both follow the cases of `infra/web/spa-routes.json`: `make test-frontend` runs the function on them, `make smoke` nginx. A new route goes into all three files.

The links of mails, `/invite`, `/reset-password` and `/confirm-email`, get the `index.html` of the login app, which reads the token from the URL fragment.

`make test-e2e` runs the Playwright tests of `frontend/e2e` in the `e2e` container against the running environment. It first hands SimpleSAMLphp the metadata of `acme`, copies the mkcert CA into `dev-tools/certs` and runs `dev-tools/e2e-invitation.sh`, which reinvites `invitee@acme.test`, `passkey@acme.test`, `account@acme.test`, `admin@acme.test` and `services@acme.test` as admins of `acme`, and `operator@ops-sso.test` and `outsider@ops-sso.test` as admins of `ops-sso`, through the operator CLI. So a run removes the password and the passkeys of the first operator, and leaves a fresh invitation for it in Mailpit. `frontend/e2e/admin-sp.spec.ts` rotates the signing key of `acme` and writes its new metadata into `dev-tools/simplesamlphp/metadata`, which the `e2e` container mounts. A report of a failed run stays in `frontend/e2e/playwright-report`. CI runs the same steps in the job `e2e`.

Every local request reaches `app` from the one address Traefik sets, so compose raises every limit by caller a hundredfold with `THROTTLE_MULTIPLIER`. To try the real limits, run `THROTTLE_MULTIPLIER=1 docker compose up -d app`. `app` refuses the setting in the `prod` profile.

The API types in `packages/api-client` are generated from `contracts/openapi` and committed. After a contract change run `docker compose run --rm frontend corepack pnpm --filter @idp/api-client generate`, or `make test-frontend` fails.

## Domains

`*.idp.test` is used instead of `.local`, which macOS reserves for mDNS. HTTPS is required everywhere because passkeys and `Secure` cookies need it outside `localhost`.

`/etc/hosts` has no wildcards, so `dev-tools/domains.sh` lists every domain. Add a new tenant there and run `make dns-mapping`. If you create tenants often, point `idp.test` at `127.0.0.1` with dnsmasq instead.

## Database

`migrator` runs on every `up`, connects to Postgres directly as `idp_owner`, and migrates `platform` and every tenant schema. Then `role-passwords` sets the passwords of the roles the migration created, because secrets do not belong in migrations.

`dev-tools/postgres/init.sql` only runs on an empty volume, so after it changes run `make clean`.

`app`, `worker` and `ops` connect through PgBouncer in transaction mode under their own roles, and every tenant transaction sets its role with `SET LOCAL`. Tests start PgBouncer from the same `dev-tools/pgbouncer` files, so a new role goes into `userlist.txt`. `worker` also connects directly as `provisioner` to create tenant schemas.

`worker` records erased users and deleted tenants by ID in a file in the `idp-erasures` volume, apart from the database. After restoring the database, apply them again with `docker compose run --rm -e REAPPLY_ERASURES=true migrator`, see [the runbook](../docs/security/restore-runbook.md).

In the `local` profile `worker` applies for the tenants `acme` and `globex` and approves them as `ops` would, bootstraps the service tenant `ops-sso`, see [Operator](#operator), then provisions them from the outbox event like any other tenant. `make up` returns after that, because the health check of `worker` waits for it. A new or changed tenant reaches `app` within `idp.tenants.cache-ttl`, 30 seconds by default.

`worker` then adds fixtures to `acme`, once, so a restart changes nothing:

| User | Password | TOTP secret | Test SP |
|---|---|---|---|
| `alice@acme.test` | `local-fixture-password-acme` | `JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP` | Assigned |
| `bob@acme.test` | `local-fixture-password-acme` | `JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP` | Not assigned |

The test SP is SimpleSAMLphp, registered from `dev-tools/simplesamlphp/sp-metadata.xml` with the email and the display name released. It signs with `rsa-sha256`, because SimpleSAMLphp 2.5.3 verifies no RSA-PSS.

## SAML

SimpleSAMLphp needs the metadata of `acme`, whose certificate is created at provisioning. After the first `make up` on fresh volumes, and after a key rotation, run:

```bash
dev-tools/simplesamlphp/fetch-idp-metadata.sh
```

Then https://sp.idp.test/simplesaml/whoami.php logs in through `acme` and shows the NameID, the attributes and the authentication context it received. Tenants created before the SP registry have no SAML identity, so `make clean` first if `/saml/metadata` answers `404`. `SamlInteropTest` runs the same configuration in a container.

## Operator

The API of `ops` needs a SAML login at the service tenant `ops-sso`: https://ops.idp.test/saml2/authenticate/ops-sso starts it, and a request without a session gets 401. The service tenant requires a passkey, which the operator registers in the login after setting the password, and `frontend/e2e/ops-login.spec.ts` walks this login. `ops` answers on `ops.idp.test` only.

`worker` seeds the service tenant like `acme`, through the same bootstrap as the CLI. Its first operator is `operator@ops-sso.test`, whose invitation is in Mailpit until Mailpit is recreated, and again after `make test-e2e`. `worker` then registers `ops` there as an SP with the email as NameID, assigns the first operator and requires passkeys. `ops` trusts the keys of a metadata file it is given, never ones it fetches itself. Locally `ops-idp-metadata` copies `https://ops-sso.idp.test/saml/metadata` into a volume on every `up`, so after a key rotation run `docker compose up -d --force-recreate ops-idp-metadata ops`.

In production the operator does the same by hand: run the CLI `bootstrap`, wait until the tenant is active, store its `/saml/metadata` where `IDP_METADATA_FILE` points, and start `ops`. `ops` does not start without the file or with the metadata of another IdP. After a key rotation at the service tenant the file is replaced between starting and completing the rotation.

The CLI is a one-off run of the same image. In production IAM decides who may start it and `sts:GetCallerIdentity` names the actor. Locally the actor is `local-operator`.

```bash
docker compose run --rm --no-deps ops java -jar /app/service.jar cli
```

Without an operation it prints the usage. It exits with 1 when an operation is refused and with 2 on wrong arguments.

## Observability

The services log one JSON object per line, so `docker compose logs app` shows JSON, see [logging.md](../docs/security/logging.md). `app`, `worker` and `ops` send traces, logs and metrics over OTLP only when they get `OTEL_EXPORTER_OTLP_ENDPOINT`, and metrics also need `OTEL_METRICS_EXPORTER=otlp`. Spring Boot maps these OpenTelemetry variables to its own properties. `make up` passes neither, so the services export nothing. Compose passes them on from the shell, so a shell that sets them for something else sends telemetry there.

`make up-observability` starts `grafana/otel-lgtm` under the `observability` profile and points the services at it. Every local request is sampled. Grafana with Tempo, Prometheus and Loki opens at http://localhost:3000 as `admin` / `admin`, and the data stays in the `idp-lgtm` volume.

A request span carries `tenant_id`. In Tempo, search with TraceQL, for example `{ resource.service.name = "app" && span.tenant_id = "<id>" }`. In Loki a line carries `tenant_id` and `correlation_id` as structured metadata, for example `{service_name="worker"} | correlation_id = "<id>"` follows a request into the events it published. Metrics take about a minute to appear, because they are pushed once a minute.

`dev-tools/lgtm` provisions the dashboard IdP overview in the folder IdP and the alerts of `logging.md`, both read only in Grafana: change the files and restart LGTM. The alerts fire in Grafana only and send nothing.

LGTM takes about 730 MiB when idle and about 790 MiB after a few hundred requests, while `app`, `worker` and `ops` take 330 to 410 MiB each. Measured on 2026-11-02 with `docker stats`.

`make stop` and `make clean` include LGTM. `make up` alone leaves a running LGTM as it is.

### Load tests

The k6 scenarios in `dev-tools/k6` run in `grafana/k6` under the `load` profile and send their metrics to LGTM, so start `make up-observability` first:

```bash
make load SCENARIO=login                 # password logins of alice up to the second factor, one after another
make load SCENARIO=saml RATE=5           # logins through SimpleSAMLphp with the session of one full login
make load SCENARIO=throttle              # 150 flows a second in globex, until Throttle refuses with 429
```

`DURATION` and `RATE` change the run. The numbers find bottlenecks and regressions, not the capacity, which staging measures. The Load tests row of the dashboard shows them by `testid`. The SAML scenario needs the metadata of `acme` in SimpleSAMLphp, see [SAML](#saml). The throttle scenario fills the bucket of the address in `globex` for about a minute, and leaves `acme` alone.

### Grafana for Claude Code

[mcp-grafana](https://github.com/grafana/mcp-grafana) lets Claude Code query traces, metrics and logs. It is added in the local scope, so it stays in `~/.claude.json` for this checkout and its token never reaches the repository.

1. In Grafana open Administration → Users and access → Service accounts, add one with the `Viewer` role and create a token for it.
2. Add the server with the token:

   ```bash
   claude mcp add --scope local grafana -e GRAFANA_SERVICE_ACCOUNT_TOKEN=<token> \
     -- docker run --rm -i --network idp_default \
       -e GRAFANA_URL=http://lgtm:3000 -e GRAFANA_SERVICE_ACCOUNT_TOKEN \
       grafana/mcp-grafana:2.1.1 -t stdio \
       --enabled-tools=prometheus,loki,tempo --disable-write --usage-stats=disabled
   ```

`--enabled-tools` leaves only the Prometheus, Loki and Tempo tools, and `--disable-write` drops every tool that changes Grafana. A `Viewer` token could not change anything either. The data sources have the UIDs `prometheus`, `loki` and `tempo`. The server runs in the compose network, so it works only while LGTM runs.

## Secrets

Every credential here is for local use only. OpenBao stores its data in a volume and is unsealed by `openbao-init` on every `up`, because keys must survive a restart as long as Postgres keeps data encrypted with them. Its token is `local-dev-token`, and `local-ops-token` given to `ops` can only encrypt with `platform-contacts`. Provisioning gives each tenant a KEK there and `openbao-init` creates the `platform-contacts` KEK, so wiping the OpenBao volume without Postgres leaves tenant keys and application contacts unreadable.
