# Authorization

Who may call which function, reach which data and read which field. A new message, response or role changes the tables below in the same pull request.

## Where it is enforced

- **Functions:** `SecurityDecorator` on the bus, on the server, before the transaction. A `*SecurityConfig` maps every message of its audience to a permission and every role to its permissions. A message without an entry is refused, `null` makes it public, and every public message is listed in `ArchitectureRules.publicMessages`. A refusal answers `403` and is audited as `access.denied`. A request without a session of the tenant answers `401`.
- **Data:** the tenant comes from the host, and every query runs under the database role and `search_path` of that tenant's schema. Ownership inside a tenant is checked by the handler.
- **Fields:** a controller returns only types of its audience, built field by field, and the `audienceresponses` rule of `ArchitectureRules` refuses a context type in a response, in a field of one or in a request. A service decrypts only the fields it is asked for. A request that names an unknown field answers `400`.

## Subjects

| Subject | Recognised by | Deployable |
|---|---|---|
| Anonymous | No session cookie, or one that resolves to no live session of the tenant | `app` |
| `USER` | A live session in the tenant's schema. The role is read from the user on every request | `app` |
| `TENANT_ADMIN` | The same, with the admin role | `app` |
| `Operator` | A SAML login at the service tenant `ops-sso` | `ops` |
| `OperatorCli` | The caller IAM lets start the one-off container | `ops` CLI |
| System | `worker` and `migrator`, which call services without the bus | `worker`, `migrator` |

## Functions

| Audience | Permission | Roles | Messages | Extra condition |
|---|---|---|---|---|
| `login` | public | anyone | Flows and their steps, accepting an invitation, password reset, confirming an email change | The flow decides the next step, a one-time token stands in for a principal |
| `login` | `email.change` | `USER`, `TENANT_ADMIN` | Ask to change the own email | Login at most 10 minutes old |
| `account` | `account.own` | `USER`, `TENANT_ADMIN` | Factors, password, sessions of the own account | Changing a factor and ending other sessions need a login at most 10 minutes old, a password change the current password |
| `account` | public | anyone | Logout | Acts only on the session of the request |
| `admin` | `admin.users` | `TENANT_ADMIN` | Search by the whole email, the user card, invite, resend the invitation, change the role, block, unblock, erase, reset MFA, end the sessions of a user or of the whole tenant | Erasing and resetting MFA need a login at most 10 minutes old |
| `admin` | `admin.policy` | `TENANT_ADMIN` | Read and save the policy | Saving needs a login at most 10 minutes old and the current `If-Match` |
| `admin` | `admin.service_providers` | `TENANT_ADMIN` | List, read, register from a metadata file and remove SPs, save their settings, list, assign and revoke their users | Saving settings needs a login at most 10 minutes old and the current `If-Match`, removing an SP a login at most 10 minutes old |
| `admin` | `admin.signing_keys` | `TENANT_ADMIN` | List the SAML signing keys, start and complete a rotation | Completing needs a login at most 10 minutes old |
| `operator` | `tenants.read` | `Operator`, `OperatorCli` | Read a tenant and an application | |
| `operator` | `tenants.manage` | `Operator`, `OperatorCli` | Submit and approve an application, suspend, resume, offboard, cancel offboarding, reinvite the admin | |
| `operator` | `tenants.bootstrap` | `OperatorCli` | Bootstrap the service tenant | The API never holds this role |
| SAML | none, outside the bus | anyone | AuthnRequest, resume, metadata | A Response goes only to a user assigned to the SP, checked after the login |

Domain rules stand on top of permissions: the last active admin of a tenant cannot step down, be blocked or be erased, and MFA of `TENANT_ADMIN` cannot be switched off.

## Data

| Subject | Reaches | Does not reach |
|---|---|---|
| Anonymous | The flow bound to their browser, the user named by a valid one-time token | Anything else |
| `USER` | Their own factors and sessions. A message of `account` names no user, the handler takes the one of the session | Other users: a passkey or a session of someone else reads as missing |
| `TENANT_ADMIN` | Every user of the tenant, themselves too, the policy, the SPs and their assignments, the signing keys | Users and SPs of other tenants read as missing, a session of another tenant counts as none, and a list of users that names one of another tenant assigns nobody |
| `Operator`, `OperatorCli` | `platform` only, under `ops_runtime` | Tenant schemas and customer data |
| An SP | The NameID and the attributes released to it, for its assigned users | Everyone else and every attribute not released |
| System | One tenant per transaction, under the role of its job | |

## Fields

| Field | The user themselves | `TENANT_ADMIN` | Operator |
|---|---|---|---|
| Email | Yes, on the sessions page | On the card, and as a search term that must match whole. Never in a list | Never. The application contact only as a mask |
| Name | No API yet | On the card | Never |
| Phone | No API yet | Never | Never |
| Role, status | Their own role, on the sessions page, so the console shows the admin pages only to an admin | On the card and in a search hit | Never |
| Password hash, TOTP secret, recovery codes, passkey keys | The TOTP secret and recovery codes once, when issued. Passkeys as ID, dates and backup state | Never. MFA can only be reset | Never |
| Sessions: IP, User-Agent, times | Their own | Never listed, only ended | Never |
| SP assignments | No | On the card, with the SP name and EntityID. The users of an SP by ID, role and status, never by email | Never |
| SP: EntityID, name, endpoints, certificate fingerprints, settings | No | Yes. The file name of the uploaded metadata is never read | Never |
| SAML signing keys | No | Status, certificate fingerprint and dates. The private key never | Never |
| Audit records about a user | No | On the card the newest 20: time, action, actor type and ID. The IP and the details stay out | The operator journal only |
| Policy | No | Yes | Never |
| Tenant | No | No | Name, status, provisioning step, offboarding date |

The admin API for the journal adds its rows here.
