# Release and rollback

How people release, check and roll back an environment. What the workflows do step by step and which rights they hold is in the section Releases of [infra/terraform/README.md](../infra/terraform/README.md#releases), why releases stay compatible in [ADR-007](adr/007-release-compatibility.md), and how releases fit the infrastructure in [ADR-009](adr/009-aws-infrastructure.md). The time frames that stop a release are in [dependencies.md](security/dependencies.md).

## A release

Run the workflow `Deploy` for the environment. A reviewer of the GitHub environment `<env>` approves the run. The inputs:

- `full` releases both sides whatever changed, also to roll out what an apply of Terraform registered.
- `skip-smoke` only for the first release of an environment, which has no synthetic tenant yet. The plan refuses it later.
- `min-client-version` before a contract step, see ADR-007. Empty keeps the minimum that runs.

The release stops before anything moves when a dependency comes from another registry, when a vulnerability or an update is past its time frame, or when the plan refuses the inputs. After the services moved, a failed step rolls the release back by itself: the smoke test, an alarm that fires in the five minutes after it, a service that does not get healthy, or `migrator`. The summary of the run shows the plan, and the artifact `sbom-<env>-<version>` holds the SBOMs and what Trivy found in them for 90 days.

| The run stopped at | Then |
|---|---|
| Registries | Remove the repository or the registry that the step names, see [dependencies.md](security/dependencies.md#registries) |
| Vulnerabilities | Update the component, or record an acceptance with a reason, see [dependencies.md](security/dependencies.md#vulnerabilities) |
| Updates | Merge the Renovate pull request, or close it with the reason in a comment |
| KMS check | KMS refuses the platform KEK to the CLI: fix the key policy or the network before releasing again |
| Smoke SSO | The run names the check that failed. A `410` after the password or the code can be the delay of the synthetic user after failed attempts, which ends within 15 minutes |
| Alarms | Look at the alarm in CloudWatch: the release is rolled back, and so is what made it fire unless the cause lies elsewhere |

## Rollback

A failed release rolls back by itself. To leave a release that went out, run the workflow `Rollback` for the environment: each run goes one release further back, first the pages, then the revision each service ran before, then the one-off tasks with its images. Migrations stay, since expand is compatible with the old code.

## The synthetic tenant

The smoke test signs in as a user of the tenant `release-smoke` through the test SP of `infra/deploy/smoke-sp-metadata.xml`. That SP holds no key: the script sends its AuthnRequest unsigned by HTTP-Redirect, walks the flow API as the login page does with the password and a TOTP code, and checks the form the IdP hands back as an SP would: the signature with a certificate of the published metadata, the Audience, Destination, Recipient, InResponseTo, the times and the MFA context. Its ACS lies under `.invalid`, so the assertion never leaves the runner. It also checks that the API answers with `X-App-Version` of the release.

Set the tenant up once per environment, after the first release with `skip-smoke`:

1. Submit and approve it with the CLI, as in the section Services of [infra/terraform/README.md](../infra/terraform/README.md#services), with a mailbox of the team as contact: `cli submit "Release smoke test" release-smoke <mailbox>`, then `cli approve <application-id>`, and `cli tenant <tenant-id>` until it says `ACTIVE`. The tenant lives at `release-smoke.<domain>`, the host that `modules/release` hands the release.
2. Accept the invitation of the first admin from the mailbox, set a password and TOTP. The login of the admin goes to the password manager of the team, never to the secret below.
3. In the console of the tenant, register the SP from `infra/deploy/smoke-sp-metadata.xml` with the name `Release smoke test` and leave its settings as they come: RSA-PSS, a signed assertion, a persistent NameID and no attributes.
4. Invite a user with the role `User` at another address of the mailbox, accept the invitation, set a password and TOTP, and note the secret the page shows. Keep the recovery codes in the password manager of the team.
5. As the admin, assign that user to the SP.
6. Write the login of the user into the secret of the output `smoke_login_secret` of the environment:

   ```bash
   aws secretsmanager put-secret-value --secret-id <smoke_login_secret> \
     --secret-string '{"login":"<address>","password":"<password>","totpSecret":"<base32 secret>"}'
   ```

7. Run `Deploy` with `full`. Its smoke test signs in once.

The role `deploy` reads that secret alone and changes nothing in it. A changed password or TOTP of the user goes into the secret before the next release. Every failed smoke test counts as a failed login of the user, so after a few of them the delay of the account runs, at most 15 minutes.

Locally `make release-smoke` sets up the same tenant on `release-smoke.idp.test` with the operator CLI and the API, and writes the login to `dev-tools/release-smoke/login.json`. `make smoke-sso` then runs the smoke test against it, see [dev-tools/README.md](../dev-tools/README.md#release-smoke-test).

## Why the smoke test has packages

XML signatures need XML canonicalization, which Node does not have. The smoke test verifies with `xml-crypto` and parses with `@xmldom/xmldom`, both pinned exactly in `infra/deploy/package.json` with a lockfile of their own, so the release scripts bring no package into the pages and the pages none into the release. `xml-crypto` is what node-saml verifies with, and it rejects a document that repeats an ID. The script refuses a document type, checks the profile of the signature before it verifies, and reads the assertion only from the canonical form that the signature covers. A test against a Response that the local IdP signed with OpenSAML proves that the two agree.
