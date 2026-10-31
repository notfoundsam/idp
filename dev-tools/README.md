# Local environment

The host needs Docker, also for compiling the backend, a JVM to run Gradle, and [mkcert](https://github.com/FiloSottile/mkcert). Node is not needed: the frontend runs in Docker.

```bash
make install   # /etc/hosts entries (asks for sudo), certificates, dependencies, jars
make up        # builds the jars, starts everything, opens the dashboard
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

Debug ports: `app` 5005, `worker` 5006, `ops` 5007. Postgres listens on `127.0.0.1:5432`.

## Domains

`*.idp.test` is used instead of `.local`, which macOS reserves for mDNS. HTTPS is required everywhere because passkeys and `Secure` cookies need it outside `localhost`.

`/etc/hosts` has no wildcards, so `dev-tools/domains.sh` lists every domain. Add a new tenant there and run `make dns-mapping`. If you create tenants often, point `idp.test` at `127.0.0.1` with dnsmasq instead.

## Database

`migrator` runs on every `up`, connects to Postgres directly as `idp_owner`, and migrates `platform` and every tenant schema. Then `role-passwords` sets the passwords of the roles the migration created, because secrets do not belong in migrations.

`dev-tools/postgres/init.sql` only runs on an empty volume, so after it changes run `make clean`.

`app` connects through PgBouncer in transaction mode as `app_runtime`, and every tenant transaction sets its role with `SET LOCAL`. Tests start PgBouncer from the same `dev-tools/pgbouncer` files, so a new role goes into `userlist.txt`.

In the `local` profile `worker` seeds the tenants `acme` and `globex`. `make up` returns after the seed, because the health check of `worker` waits for it. A new or changed tenant reaches `app` within `idp.tenants.cache-ttl`, 30 seconds by default.

## Secrets

Every credential here is for local use only. OpenBao stores its data in a volume and is unsealed by `openbao-init` on every `up`, because keys must survive a restart as long as Postgres keeps data encrypted with them. Its token is `local-dev-token`.
