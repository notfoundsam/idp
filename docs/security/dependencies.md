# Dependencies

Where third-party components come from, how a release lists them, and how long a known vulnerability or an update may wait. The workflow `Deploy` checks all three before anything moves and stops the release on a breach, see [docs/release.md](../release.md). This covers V15.1.1, V15.1.2 and V15.2.1 in [asvs-l2.md](asvs-l2.md).

## Registries

| Ecosystem | Registry | Where it is set |
|---|---|---|
| Gradle plugins | Gradle Plugin Portal, Maven Central | `pluginManagement` of `backend/settings.gradle.kts` |
| Java libraries | Maven Central | `dependencyResolutionManagement`, which refuses repositories of a project |
| OpenSAML | Shibboleth, only for `org.opensaml` and `net.shibboleth` through `exclusiveContent`, see [ADR-004](../adr/004-saml-library-and-keys.md) | the same |
| Gradle | `services.gradle.org` with `distributionSha256Sum` | `backend/gradle/wrapper/gradle-wrapper.properties` |
| npm packages | `https://registry.npmjs.org/` | `.npmrc` of `frontend` and of `infra/deploy`, each with its lockfile |

`infra/deploy/registries.mjs` fails on any other repository in a Gradle script, a Shibboleth repository outside `exclusiveContent`, another or a scoped npm registry, and a package in a lockfile that comes from a tarball, git or a file. `make check-registries` runs it in the CI job `infra`, and the release runs it first. Gradle resolves the exact versions of `backend/gradle/libs.versions.toml` and the Spring Boot BOM, pnpm installs from its lockfiles with the integrity hashes of the registry and takes only versions at least 7 days old, as Renovate does.

## SBOM

Every release writes CycloneDX 1.6 SBOMs of what it ships into `build/sbom`, `make sbom` locally:

- `backend.cdx.json`: the runtime classpath of `app`, `worker`, `ops` and `migrator` as Gradle resolves it for each jar, by the plugin `org.cyclonedx.bom` of the version catalog. Tests and build tools stay out.
- `idp-login.cdx.json` and `idp-console.cdx.json`: the production packages of each app from the lockfile, by `pnpm sbom` of the pnpm pinned in `frontend/package.json`, in the `frontend` container.
- `image-<name>.cdx.json`: every image that runs once the release is live, the backend images of the release and those of `infra/` under `infra_image_tag`, with the OS packages of their base images, by `make image-sbom` with Trivy from ECR. The images exist only after the build, so the release scans them after pushing them and before any revision names them.

The run keeps them with the reports of Trivy as the artifact `sbom-<env>-<version>` for 90 days, the most GitHub keeps an artifact of a public repository. The git tag `release/<env>/<version>` names the commit, from which `make sbom` writes the same lists again.

## Vulnerabilities

`make vulnerabilities` matches the SBOMs with the database of Trivy, in the image `aquasec/trivy` pinned in `docker-compose.yml`, and `infra/deploy/vulnerabilities.mjs` judges the findings. A vulnerability is remediated within its time frame, counted from the publication of its advisory:

| Severity | Time frame |
|---|---|
| Critical | 7 days |
| High | 30 days |
| Medium | 90 days |
| Low | 180 days |
| Unknown | 90 days |

Remediated means that no shipped component has it: updated, replaced or removed. A finding past its time frame stops the release, one within it shows as a warning in the run. A finding without a publication date counts as past its time frame, and so does an SBOM without components, so a gap never reads as a clean result.

When no fix exists or the vulnerable code cannot be reached, an entry in `infra/deploy/accepted-vulnerabilities.json` lets releases go on: the advisory `id`, the `package` as Trivy names it, the `reason` and the date `until`, at most 90 days ahead. The pull request that adds it is the review of that decision, and an expired entry stops the release again.

A version that a library asks for and that is past its time frame is overridden by a constraint in `backend/build.gradle.kts`, as for Bouncy Castle and Guava that OpenSAML 5.2.3 pulls in.

## Updates

Renovate opens a pull request for every new version at least 7 days old and labels it `dependencies`, a major one also `major`. An update is merged, or closed with the reason in a comment, within:

| Update | Time frame |
|---|---|
| Patch or minor | 30 days |
| Major | 90 days |

`infra/deploy/updates.mjs` reads the open pull requests with `gh pr list` and stops the release on one older than its time frame. A version that `renovate.json` holds back, such as Kotlin for CodeQL, gets no pull request, and its reason stays in that rule. A vulnerability with a fix takes the shorter time frame above.

## What else watches

Pull requests run `dependency-review`, which fails on a new dependency with a vulnerability of high severity or above, and every push to `main` sends the dependency graph to GitHub for its alerts. ECR scans every image on push and stops nothing, the release gate above covers the images. These cover the build tools and tests, which the SBOMs leave out.
