<script setup lang="ts">
import type { Factor } from '@idp/api-client'
import { computed, ref } from 'vue'
import type { Login } from '../login'
import { signInWithPasskey } from '../webauthn'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{
  login: Login
  credentials: CredentialsContainer | null
}>()

const PREFERENCE: readonly Factor[] = ['PASSKEY', 'TOTP', 'RECOVERY_CODE']

const NAMES = new Map<Factor, string>([
  ['PASSKEY', 'a passkey'],
  ['TOTP', 'an authenticator app'],
  ['RECOVERY_CODE', 'a recovery code'],
])

const offered = computed(() =>
  PREFERENCE.filter((factor) => props.login.state.flow?.factors.includes(factor) && (factor !== 'PASSKEY' || props.credentials)),
)
const chosen = ref<Factor | undefined>(offered.value[0])
const code = ref('')

function choose(factor: Factor) {
  chosen.value = factor
  code.value = ''
}

function submitCode() {
  const entered = code.value.trim()
  const client = props.login.client
  return props.login.submit((id) =>
    chosen.value === 'TOTP' ? client.submitTotp(id, entered) : client.submitRecoveryCode(id, entered),
  )
}

async function passkey(credentials: CredentialsContainer) {
  const options = await props.login.fetchFor((id) => props.login.client.beginPasskeyLogin(id))
  if (options === null) return
  await props.login.submit(async (id) => props.login.client.submitPasskey(id, await signInWithPasskey(credentials, options)))
}
</script>

<template>
  <section data-step="SECOND_FACTOR">
    <div v-if="chosen === 'PASSKEY' && credentials">
      <p>Confirm with your passkey.</p>
      <ProblemMessage :state="login.state" />
      <button type="button" :disabled="login.state.busy" @click="passkey(credentials)">Use passkey</button>
    </div>
    <form v-else-if="chosen === 'TOTP'" @submit.prevent="submitCode">
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
      <ProblemMessage :state="login.state" />
      <button type="submit" :disabled="login.state.busy">Verify</button>
    </form>
    <form v-else-if="chosen === 'RECOVERY_CODE'" @submit.prevent="submitCode">
      <label for="recovery-code">Recovery code</label>
      <input
        id="recovery-code"
        v-model="code"
        name="recovery-code"
        type="text"
        autocomplete="off"
        autocapitalize="none"
        spellcheck="false"
        required
      />
      <ProblemMessage :state="login.state" />
      <button type="submit" :disabled="login.state.busy">Verify</button>
    </form>
    <p v-else role="alert">This browser cannot use any of the methods your account has.</p>
    <ul v-if="offered.length > 1">
      <li v-for="factor in offered.filter((it) => it !== chosen)" :key="factor">
        <button type="button" @click="choose(factor)">Use {{ NAMES.get(factor) }} instead</button>
      </li>
    </ul>
  </section>
</template>
