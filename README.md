# IdP

A multi-tenant Identity Provider. Service Providers authenticate users through SAML, and each tenant manages its own users, policies and Service Providers.

```
backend/    Kotlin and Spring Boot, one monolith with several deployables
frontend/   Vue 3 pnpm workspace
contracts/  OpenAPI contracts
docs/adr/   Architecture decisions
```

## Requirements

Docker, and a JVM to run Gradle. The frontend runs in Docker, so Node is not needed on the host.

## Commands

See [dev-tools/README.md](dev-tools/README.md) for the local environment.

```bash
make install
make up
make test
make lint
make build
```

## License

MIT. See [LICENSE](LICENSE).
