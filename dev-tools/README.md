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
| https://acme.idp.test, https://globex.idp.test | Tenants, `/api` and `/saml` go to `app` |
| http://localhost:3000 | Grafana, only after `make up-observability` |

Debug ports: `app` 5005, `worker` 5006, `ops` 5007. Postgres listens on `127.0.0.1:5432`.

## Domains

`*.idp.test` is used instead of `.local`, which macOS reserves for mDNS. HTTPS is required everywhere because passkeys and `Secure` cookies need it outside `localhost`.

`/etc/hosts` has no wildcards, so `dev-tools/domains.sh` lists every domain. Add a new tenant there and run `make dns-mapping`. If you create tenants often, point `idp.test` at `127.0.0.1` with dnsmasq instead.

## Database

`migrator` runs on every `up`, connects to Postgres directly as `idp_owner`, and migrates `platform` and every tenant schema. Then `role-passwords` sets the passwords of the roles the migration created, because secrets do not belong in migrations.

`dev-tools/postgres/init.sql` only runs on an empty volume, so after it changes run `make clean`.

`app`, `worker` and `ops` connect through PgBouncer in transaction mode under their own roles, and every tenant transaction sets its role with `SET LOCAL`. Tests start PgBouncer from the same `dev-tools/pgbouncer` files, so a new role goes into `userlist.txt`. `worker` also connects directly as `provisioner` to create tenant schemas.

`worker` records erased users and deleted tenants by ID in a file in the `idp-erasures` volume, apart from the database. After restoring the database, apply them again with `docker compose run --rm -e REAPPLY_ERASURES=true migrator`, see [the runbook](../docs/security/restore-runbook.md).

In the `local` profile `worker` applies for the tenants `acme` and `globex` and approves them as `ops` would, then provisions them from the outbox event like any other tenant. `make up` returns after that, because the health check of `worker` waits for it. A new or changed tenant reaches `app` within `idp.tenants.cache-ttl`, 30 seconds by default.

## Operator

In the `local` profile the API of `ops` acts as the fixed operator `local-operator`, because SAML arrives in a later task. Elsewhere a request without a principal gets 401. Until then the API stays inside compose.

The CLI is a one-off run of the same image. In production IAM decides who may start it and `sts:GetCallerIdentity` names the actor. Locally the actor is `local-operator`.

```bash
docker compose run --rm --no-deps ops java -jar /app/service.jar cli
```

Without an operation it prints the usage. It exits with 1 when an operation is refused and with 2 on wrong arguments.

## Observability

`app`, `worker` and `ops` send traces and metrics over OTLP only when they get `OTEL_EXPORTER_OTLP_ENDPOINT`, and metrics also need `OTEL_METRICS_EXPORTER=otlp`. Spring Boot maps these OpenTelemetry variables to its own properties. `make up` passes neither, so the services export nothing. Compose passes them on from the shell, so a shell that sets them for something else sends telemetry there.

`make up-observability` starts `grafana/otel-lgtm` under the `observability` profile and points the services at it. Every local request is sampled. Grafana with Tempo, Prometheus and Loki opens at http://localhost:3000 as `admin` / `admin`, and the data stays in the `idp-lgtm` volume. Logs do not reach Loki yet.

A request span carries `tenant_id`. In Tempo, search with TraceQL, for example `{ resource.service.name = "app" && span.tenant_id = "<id>" }`. Metrics take about a minute to appear, because they are pushed once a minute.

LGTM takes about 730 MiB when idle and about 790 MiB after a few hundred requests, while `app`, `worker` and `ops` take 330 to 410 MiB each. Measured on 2026-11-02 with `docker stats`.

`make stop` and `make clean` include LGTM. `make up` alone leaves a running LGTM as it is.

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
