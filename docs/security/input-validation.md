# Input validation

The rules for every input the IdP takes from outside, alone and in combination, and the answer to an input that breaks them. A new input adds its row here in the same pull request. Its contract in `contracts/openapi` carries what JSON Schema can say: types, formats, enums, numeric ranges and list sizes.

## Where it is checked

- **On the server.** The login pages and the console check a field only to help the person typing, and the server checks it again. A rule that lives only in the frontend is not a rule.
- **Size first**, see [limits.md](limits.md): a body over 32 KiB, an upload over 160 KiB and a list of more than 500 users are refused before anything reads them.
- **Binding**, in the audience: a body is JSON bound to a fixed type and decoded once. An unknown field, a missing required one, a value of the wrong type, and a malformed UUID, time or enum value answer `400` with no body, in a body, a path or a query alike. A number that does not fit its type is malformed too, so no input wraps around. A query parameter the contract does not list is ignored.
- **Domain:** a value type checks itself when it is built, and a broken rule is a `DomainRuleViolation` that answers `422` with no body, because its message may quote the input.
- **Contracts:** `auth.yaml` for the login, `account.yaml`, `admin.yaml` and `ops.yaml`. The contract tests check that the controllers and the contract name the same operations, request fields and query parameters, and that every response carries exactly the fields of its schema (`FlowContractTest`, `AccountContractTest`, `AdminContractTest`, `OpsContractTest`).

## Values

| Input | Where | Rule | Otherwise |
|---|---|---|---|
| Email | Invitation, change of the own email, contact of an application or a reinvite | Trimmed, at most 254 characters, one `@` with no whitespace (`Email`, `requireContact`) | `422` |
| Login, searched email | Login flow, user search | Any string, looked up by its blind index, so it matches a whole address or nobody | An unknown login walks the same steps as a real one, see [authentication.md](authentication.md). A search finds nobody |
| Password | Invitation, reset, change | 8 to 128 code points, not on the banned list, no word of the user's context or the product (`PasswordPolicy`) | `422`. A login with a longer one is a wrong password and is never hashed |
| TOTP code, recovery code, mail token | Login, account, recovery | Any string, compared with what is stored | A wrong credential, `422`, or `410` for an expired token |
| Passkey | Login, enrollment | base64url decoded once, then checked by the WebAuthn verifier against the challenge | `400` for a value that is no base64url, a refused credential otherwise |
| Role, SP settings, target type of the journal | Admin API | One of the values the contract lists, in upper case | `400` |
| Time and ID of a journal cursor | `GET /api/admin/audit` | An ISO 8601 instant and a UUID | `400` |
| Session timeouts of the policy | `PUT /api/admin/policy` | Idle 300 to 3,600 seconds, absolute from the idle one up to 86,400 (`TenantPolicy`) | `422` |
| Page of the journal | `GET /api/admin/audit` | `limit` 1 to 100, 50 when left out | `400` |
| `If-Match` | Policy, SP settings | A quoted version of at most 18 digits | `428` without it, `412` for another version or another form |
| Users of an assignment | `POST .../users`, `.../users/removal` | 1 to 500 UUIDs, none null | `400` |
| Name of an SP | Upload | 1 to 200 characters after trimming | `422` |
| Subdomain | Application | A DNS label in lower case, not punycode, not reserved (`Subdomain`) | `422` |
| Company name | Application | Any text within the body limit. Only the operator enters it, behind VPN and IAM | |
| SAML messages | `/saml/*` | See [limits.md](limits.md) and `AuthnRequestValidator`: one value per parameter, size, age, issuer, signature and ACS of a registered SP | Error page or SAML error at the ACS |

## Combinations

| Items | Rule | Otherwise |
|---|---|---|
| Idle and absolute timeout of the policy | The absolute timeout is at least the idle one | `422` |
| `before` and `beforeId` of the journal | Both or neither, a cursor is a time and an ID together | `400` |
| `targetType` and `targetId` of the journal | Both or neither | `400` |
| Metadata that asks for signed AuthnRequests | Must carry a signing certificate | `422` |
| ACS endpoints of metadata | At least one for HTTP-POST, each index once | `422` |
| SP settings | NameID format, signed part, algorithm and attributes are independent, every combination of listed values is valid | |
| MFA of the policy | No field switches it off for `TENANT_ADMIN`, and a body that names one is refused as an unknown field | `400` |

## Upload of SP metadata

`POST /api/admin/service-providers` is the only upload.

- **Form:** `multipart/form-data` with the parts `name` and `metadata`. Another content type answers `415`, a missing part `400`. The file name and the content type of the part are never read: what the bytes are is decided by parsing them.
- **Type:** SAML 2.0 metadata of one Service Provider: XML that validates against the SAML metadata schema and whose root is one `EntityDescriptor` with an `SPSSODescriptor`. Aggregates, other roles and other formats are refused. No extension is expected, since the name is ignored.
- **Size:** 160 KiB for the whole request, held in memory and never written to disk, then at most 128 KiB for the file itself, checked before parsing. Nothing is unpacked: compressed content is not accepted.
- **Parsing:** the XML parser refuses DTDs, external entities and XInclude, so no file reaches the network or the disk.
- **A bad file:** over 160 KiB answers `413` before it is read. Over 128 KiB, malformed, invalid against the schema, carrying a DTD, or breaking the rules above answers `422` with no body and stores nothing, since a parser message may quote the content. The same EntityID twice answers `409`.
- **What stays:** the EntityID, the ACS endpoints, the signing certificates and the flag for signed requests, in the tenant schema. The file is not kept and is never served back, so nobody downloads what an admin uploaded.
