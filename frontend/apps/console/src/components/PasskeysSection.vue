<script setup lang="ts">
import type { Factors } from '@idp/api-client'
import { registerPasskey } from '@idp/ui'
import { useConsole } from '../context'
import { dateOf } from '../format'
import { FACTOR_REQUIRED } from '../messages'
import { FACTORS, useChange } from '../queries'
import { useAccountStore } from '../store'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{
  factors: Factors
  email: string | null
}>()

const { client, browser } = useConsole()
const store = useAccountStore()

/** The options name no user, so the passkey is labelled with the email, or with the host. */
const add = useChange(
  async (credentials: CredentialsContainer) => {
    const options = await client.beginPasskey()
    return client.addPasskey(await registerPasskey(credentials, options, props.email ?? browser.host))
  },
  [FACTORS],
  (result) => store.factorChanged(result.recoveryCodes),
)
const remove = useChange((id: string) => client.removePasskey(id), [FACTORS], () => store.factorChanged())
</script>

<template>
  <section aria-labelledby="passkeys-heading">
    <h2 id="passkeys-heading">Passkeys</h2>
    <ul v-if="factors.passkeys.length > 0">
      <li v-for="passkey in factors.passkeys" :key="passkey.id" data-testid="passkey">
        <span>Added {{ dateOf(passkey.createdAt) }}</span>,
        <span>{{ passkey.lastUsedAt ? `last used ${dateOf(passkey.lastUsedAt)}` : 'never used' }}</span>,
        <span>{{ passkey.backedUp ? 'synced to your other devices' : 'kept on one device' }}</span>
        <button
          type="button"
          :disabled="remove.isPending.value"
          :aria-label="`Remove the passkey added ${dateOf(passkey.createdAt)}`"
          @click="remove.mutate(passkey.id)"
        >
          Remove
        </button>
      </li>
    </ul>
    <p v-else data-testid="no-passkeys">No passkey is registered.</p>
    <ProblemMessage :error="remove.error.value" :rejected="FACTOR_REQUIRED" />
    <ProblemMessage :error="add.error.value" />
    <button v-if="browser.credentials" type="button" :disabled="add.isPending.value" @click="add.mutate(browser.credentials)">
      Add a passkey
    </button>
    <p v-else>This browser cannot create passkeys.</p>
  </section>
</template>
