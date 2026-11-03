<script setup lang="ts">
import { grouped, otpauthUri, registerPasskey } from '@idp/ui'
import { computed, ref } from 'vue'
import type { Login } from '../login'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{
  login: Login
  credentials: CredentialsContainer | null
  host: string
}>()

const factors = computed(() => props.login.state.flow?.factors ?? [])
const secret = ref<string | null>(null)
const code = ref('')

async function beginTotp() {
  const enrollment = await props.login.fetchFor((id) => props.login.client.beginTotpEnrollment(id))
  if (enrollment !== null) secret.value = enrollment.secret
}

function confirmTotp() {
  const entered = code.value.trim()
  return props.login.enroll((id) => props.login.client.confirmTotpEnrollment(id, entered))
}

async function passkey(credentials: CredentialsContainer) {
  const options = await props.login.fetchFor((id) => props.login.client.beginPasskeyEnrollment(id))
  if (options === null) return
  const label = props.login.state.login ?? props.host
  await props.login.enroll(async (id) =>
    props.login.client.finishPasskeyEnrollment(id, await registerPasskey(credentials, options, label)),
  )
}
</script>

<template>
  <section data-step="ENROLL_MFA">
    <p>Your organisation asks for a second way to prove it is you. Set one up to go on.</p>
    <form v-if="secret" @submit.prevent="confirmTotp">
      <p>Add this key to your authenticator app, or open the link on this device.</p>
      <p>
        <code data-testid="totp-secret">{{ grouped(secret) }}</code>
      </p>
      <p><a :href="otpauthUri(secret, host, login.state.login)">Open in an authenticator app</a></p>
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
      <button type="submit" :disabled="login.state.busy">Confirm</button>
    </form>
    <template v-else>
      <ProblemMessage :state="login.state" />
      <button v-if="factors.includes('PASSKEY') && credentials" type="button" :disabled="login.state.busy" @click="passkey(credentials)">
        Register a passkey
      </button>
      <button v-if="factors.includes('TOTP')" type="button" :disabled="login.state.busy" @click="beginTotp">
        Set up an authenticator app
      </button>
    </template>
  </section>
</template>
