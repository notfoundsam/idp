# Restoring the database from a backup

Use this after a restore of a snapshot or of a point in time. The restored database still holds every user erased and every tenant deleted after that moment. The erasure journal lives outside the database and lists them, and `migrator` applies them again before anything serves the data.

## 1. Stop everything that reads the data

Scale `app`, `worker` and `ops` to zero before the restore and keep them there until step 3 succeeds.

`app` would let an erased user log in, `worker` would send them mail from events in the restored outbox, and both would serve a deleted tenant whose schema came back. `ops` holds no customer data, but an operator action on a tenant that is about to go would be lost.

## 2. Restore the database

Restore as usual. Do not touch the journal bucket: Object Lock keeps its entries for the retention period, and neither the task roles nor an operator can delete them.

## 3. Apply the erasures again

Run the `migrator` task once with `REAPPLY_ERASURES=true`. In this mode it needs, beside its usual settings:

| Setting | Value |
|---|---|
| `ERASURE_JOURNAL_BUCKET`, `ERASURE_JOURNAL_REGION` | The journal bucket, as `worker` has it |
| `KMS_NEW_KEYS`, `KMS_AWS_REGION`, `KMS_AWS_KEY_POLICY` | As `worker` has them |
| `PLATFORM_KEK_URI`, `TENANTS_BASE_DOMAIN` | As `worker` has them |

While tenants move to another KMS, `worker` has the settings of both, and so does this task: a backup may name KEKs of either.

The task migrates as on a release, then reads the whole journal:

- A deleted tenant loses its KEKs, its schema and its role, and becomes `DELETED`. It does not come back as `ACTIVE`, whatever status the backup kept.
- An erased user of a remaining tenant goes with everything that hangs on the `users` row, their key in `user_keys` included. The rule that keeps the last admin does not hold the erasure back. The tenant journal records `user.erased` by the system.

Everything is safe to repeat. A user or tenant that the backup does not hold is skipped.

The log ends with `Erasures applied again: <n> tenants, <m> users`. Exit code `0` means done. Anything else means the journal or KMS could not be read: fix that and run the task again. Do not start `app` and `worker` before it exits with `0`.

## 4. Start the services

Start `worker`, `app` and `ops`, then check the dashboards and the dead events in the outbox.

## What the journal can and cannot do

`worker` writes the journal. A user goes in when `worker` handles `user.erased`, usually within a second of the erasure. A tenant goes in before its KEK is destroyed, so a backup taken in the middle of offboarding still loses the tenant here.

An erasure from the last moments before the incident can miss the journal if `worker` had not handled its event yet. The restored database then holds that user with no trace of the request. Ask support for erasure requests from that window and repeat them.

Entries hold IDs alone, for example `users/<tenant-id>/<user-id>`. The journal must keep an entry for as long as a backup taken before it exists: 30 days of backups plus a margin. In AWS Object Lock keeps every entry for 45 days, then the lifecycle of the bucket removes it, see `infra/terraform/README.md`. An older entry is not needed, because every remaining backup was taken after it.

A deleted tenant needs no journal to stay unreadable: its data in every backup is encrypted under a destroyed KEK. AWS KMS waits 7 days before it deletes the key material. Never cancel a pending deletion of a tenant KEK, the journal will not destroy it again after a restore.

## Locally

The journal is a file in the `idp-erasures` volume, apart from the database.

```bash
docker compose run --rm -e REAPPLY_ERASURES=true migrator
```
