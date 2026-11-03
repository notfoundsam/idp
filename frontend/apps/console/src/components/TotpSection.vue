<script setup lang="ts">
import type { Factors } from '@idp/api-client'
import { grouped, otpauthUri } from '@idp/ui'
import { ref } from 'vue'
import { useConsole } from '../context'
import { CODE_REFUSED } from '../messages'
import { FACTORS, useChange } from '../queries'
import { useAccountStore } from '../store'
import ProblemMessage from './ProblemMessage.vue'

defineProps<{
  factors: Factors
  email: string | null
}>()

const { client, browser } = useConsole()
const store = useAccountStore()
const secret = ref<string | null>(null)
const code = ref('')

const begin = useChange(() => client.beginTotp(), [], (setup) => {
  secret.value = setup.secret
  code.value = ''
})
const confirm = useChange((entered: string) => client.confirmTotp(entered), [FACTORS], (result) => {
  secret.value = null
  store.factorChanged(result.recoveryCodes)
})
const remove = useChange(() => client.removeTotp(), [FACTORS], () => store.factorChanged())

function cancel() {
  secret.value = null
  confirm.reset()
}
</script>

<template>
  <section aria-labelledby="totp-heading">
    <h2 id="totp-heading">Authenticator app</h2>
    <template v-if="factors.totp">
      <p data-testid="totp-status">An authenticator app is set up.</p>
      <ProblemMessage :error="remove.error.value" />
      <button type="button" :disabled="remove.isPending.value" @click="remove.mutate(undefined)">Remove authenticator app</button>
    </template>
    <form v-else-if="secret" data-testid="totp-form" @submit.prevent="confirm.mutate(code.trim())">
      <p>Add this key to your authenticator app, or open the link on this device.</p>
      <p>
        <code data-testid="totp-secret">{{ grouped(secret) }}</code>
      </p>
      <p><a :href="otpauthUri(secret, browser.host, email)">Open in an authenticator app</a></p>
      <label for="totp">Code from your authenticator app</label>
      <input
        id="totp"
        v-model="code"
        name="totp"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        pattern="[0-9 ]*"
        required
      />
      <ProblemMessage :error="confirm.error.value" :rejected="CODE_REFUSED" />
      <button type="submit" :disabled="confirm.isPending.value">Confirm</button>
      <button type="button" @click="cancel">Cancel</button>
    </form>
    <template v-else>
      <p data-testid="totp-status">No authenticator app is set up.</p>
      <ProblemMessage :error="begin.error.value" />
      <button type="button" :disabled="begin.isPending.value" @click="begin.mutate(undefined)">Set up an authenticator app</button>
    </template>
  </section>
</template>
