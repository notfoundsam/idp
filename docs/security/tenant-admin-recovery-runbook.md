# Recovering a tenant that lost its admin

Use this when a customer has no working tenant admin: the only admin lost every factor, left the company, or never accepted the first invitation before it expired. Any other user goes to their tenant admin, who resets the factors.

This command hands a tenant to whoever holds the address. Nothing inside the system can check that person, so the check below is the whole protection. Recommend every customer to keep two admins, so this runbook is never needed.

## 1. Prove the customer outside the system

Do not act on an email alone, and do not trust a phone number or an address given in the request.

1. Find the signed contract and the contacts named in it, not those in the request.
2. Call a contact back on a number from the contract or from a public source of the company.
3. Have that contact confirm in writing who the new admin is and at which address.
4. Have a second operator review the evidence before step 2. Record both names and the evidence in the support ticket.

Refuse if a contact cannot be reached, if the request is urgent and asks to skip a step, or if the address is not on a domain of the customer. A refused request goes to the security contact as a possible takeover.

## 2. Run the command

```bash
cli reinvite <tenant-id> <address>
```

The API offers the same as `POST /api/tenants/{id}/admin-reinvite`. The tenant must be active: resume a suspended tenant first, or leave this for after the suspension ends.

The answer is the same whether the tenant knows the address or not, because an operator sees no data of a tenant. The address is sealed with the platform KEK, which `ops` can encrypt with and cannot decrypt, and `worker` deletes it after use. The operator journal records who ran the command and for which tenant, without the address.

## 3. What happens in the tenant

`worker` handles the event:

| The address | Result |
|---|---|
| Belongs to nobody | A new user is invited as `TENANT_ADMIN` |
| Belongs to a user who has not accepted | The invitation is replaced by a new one, and the role becomes `TENANT_ADMIN` |
| Belongs to an active or blocked user | That user goes back to invited as `TENANT_ADMIN`. Every factor goes, all sessions end, and waiting recovery links stop working |

The invitation lives for 72 hours. After accepting, the admin sets a password and enrolls a second factor, which a tenant cannot relax for this role. The journal of the tenant shows `user.reinvited` as a system action, or `user.created` for a new user, together with `session.revoked_for_user`.

The old admin stays in the tenant under another address. The new admin should review the users and block or remove that account.

## 4. Close the ticket

1. Confirm with the customer that the new admin logged in.
2. Tell the customer to review the journal and add a second admin.
3. If nobody accepted within 72 hours, go back to step 1. An invitation that expired is not a reason to skip the check.
