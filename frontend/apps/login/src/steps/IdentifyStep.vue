<script setup lang="ts">
import { ref } from 'vue'
import type { Login } from '../login'
import { signInWithPasskey } from '../webauthn'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{
  login: Login
  credentials: CredentialsContainer | null
}>()

const name = ref(props.login.state.login ?? '')

function next() {
  const entered = name.value.trim()
  props.login.remember(entered)
  return props.login.submit((id) => props.login.client.submitLogin(id, entered))
}

async function passkey(credentials: CredentialsContainer) {
  const options = await props.login.fetchFor((id) => props.login.client.beginPasskeyLogin(id))
  if (options === null) return
  await props.login.submit(async (id) => props.login.client.submitPasskey(id, await signInWithPasskey(credentials, options)))
}
</script>

<template>
  <form data-step="IDENTIFY" @submit.prevent="next">
    <label for="login">Email</label>
    <input
      id="login"
      v-model="name"
      name="username"
      type="text"
      inputmode="email"
      autocomplete="username webauthn"
      autocapitalize="none"
      spellcheck="false"
      required
    />
    <ProblemMessage :login="login" />
    <button type="submit" :disabled="login.state.busy">Next</button>
  </form>
  <button v-if="credentials" type="button" :disabled="login.state.busy" @click="passkey(credentials)">
    Sign in with a passkey
  </button>
</template>
