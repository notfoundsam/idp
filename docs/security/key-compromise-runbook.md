# Responding to a compromised tenant key

Use this when someone outside the services may hold the tenant key of a tenant, its blind index key, or the right to decrypt with its KEK. The keys are described in [crypto-inventory.md](crypto-inventory.md).

The first version cannot rotate a tenant key or compute the blind index again. The steps below contain the incident and limit its reach, and step 5 names what replaces the key when that is needed.

## What each key gives away

| Leaked | With a copy of the tenant schema or a backup | Without one |
|---|---|---|
| Tenant key | Every user key, so all personal data of the tenant, the audit details of the system, logins typed into flows, and the blind index key | Nothing: the tenant key encrypts no data itself |
| Blind index key | Whether a guessed address belongs to a user of the tenant. Not the addresses themselves | Nothing |
| `Decrypt` on the tenant KEK | The tenant key, then as above | Nothing |
| The KEK itself | Cannot leak: AWS KMS keeps it in its HSMs, and OpenBao transit keys are not exportable | |

Passwords, tokens, recovery codes and passkeys are hashes or public keys, and no tenant key opens them.

The blind index key is derived from the tenant key, so a leaked tenant key means a leaked blind index key. The blind index key alone leaks only from the memory of a service, the same way the tenant key does.

## 1. Close the path

Find how the key left and close that path before anything else. Do not wait for the assessment.

| Path | Action |
|---|---|
| `Decrypt` calls on the tenant KEK from a principal other than the task roles of `app`, `worker` and `recovery` | The key policy denies them to everybody else, so somebody changed it: put back the output `tenant_kek_policy` of `infra/terraform` with `PutKeyPolicy` and find who changed it in CloudTrail. On OpenBao revoke the token. The KEK stays: its material never left the KMS |
| Memory of a running task, for example a heap dump or remote code execution | Replace every task of the affected service from a known image. A new task holds no unwrapped key until it reads one again |
| A leaked database credential, snapshot or backup | Rotate the credential, stop the snapshot sharing or export, and treat the data as copied in step 2 |

## 2. Assess what was copied

The key matters only together with data. Check, for the window of the incident:

1. CloudTrail, in the log group `/<env>/cloudtrail` for the last year: `Decrypt` on the tenant KEK by unexpected principals, `PutKeyPolicy` on it, and RDS `CopyDBSnapshot`, `ModifyDBSnapshotAttribute`, `StartExportTask`.
2. Postgres logs: logins of unknown roles or from unknown addresses, and reads of the tenant schema outside `app`, `worker` and `migrator`.
3. Which tenants are affected. A tenant key opens one tenant only.

Write the result into the incident ticket: the tenants, whether a copy of their data is likely, and the window.

## 3. Notify

If a copy of the data is likely, the personal data of the tenant counts as disclosed. Tell the tenant admins and the data protection contact at once, so the customer can meet a deadline of 72 hours, for example under GDPR. A leaked blind index key with a copy discloses only which guessed addresses are users.

If no copy is likely and the path is closed, record the decision not to notify and its reasons in the ticket.

## 4. Watch

Until step 5 is done, watch every `Decrypt` of the tenant KEK outside the task roles, and every export or sharing of a snapshot. The alarms `kms-decrypt`, `kms-key-policy` and `rds-snapshot-export` of `infra/terraform/modules/alarms` already fire on them in AWS, so check that they are in the state OK and that the on-call engineers hear from the topic `<env>-alarms`.

## 5. Replace the key when data was copied or the path cannot be proven closed

A new KEK does not help: the tenant key stays the same under it. The tenant key itself must change. The first version offers two ways:

- **Rotate the tenant key as an incident change.** `tenant_keys.version` and the key ID in every Tink ciphertext leave room for a second tenant key. The change adds version 2, wraps every user key again under it, computes the blind index again from the decrypted addresses and encrypts the audit details of the system again. Flows expire on their own. Once no data uses version 1, delete its row. Backups keep version 1 until they expire.
- **Move the customer to a new tenant.** A new tenant gets a new KEK and a new tenant key. Users are invited again and enroll their factors again, SPs are registered again. Offboard the old tenant afterwards, which destroys its KEK and every backup's access to its data.

Choose with the customer: the first keeps their users and setup, the second is ready at once.

## 6. Close the incident

1. Confirm that the path from step 1 stays closed.
2. Record what leaked, the tenants, the decision of step 5 and the notifications in the ticket.
3. If a principal or a service was the path, add the missing control to [asvs-l2.md](asvs-l2.md) or to the alerts.
