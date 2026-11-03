<script setup lang="ts">
import type { Client } from '@idp/api-client'
import { ref } from 'vue'
import { LOGIN_PATH } from '../pages'
import { createRecovery } from '../recovery'
import ProblemMessage from '../steps/ProblemMessage.vue'

const props = defineProps<{ client: Client }>()

const recovery = createRecovery()
const login = ref('')

function send() {
  const entered = login.value.trim()
  return recovery.send(() => props.client.requestPasswordReset(entered))
}
</script>

<template>
  <h1>Reset your password</h1>
  <p v-if="recovery.state.done" role="status" data-testid="reset-requested">
    If an account uses this address, a mail with a link to set a new password is on its way. A new request cancels the link of
    the previous one.
  </p>
  <form v-else @submit.prevent="send">
    <p>Enter the email address you sign in with, and we send a link to set a new password.</p>
    <label for="login">Email</label>
    <input
      id="login"
      v-model="login"
      name="username"
      type="text"
      inputmode="email"
      autocomplete="username"
      autocapitalize="none"
      spellcheck="false"
      required
    />
    <ProblemMessage :state="recovery.state" />
    <button type="submit" :disabled="recovery.state.busy">Send link</button>
  </form>
  <p><a :href="LOGIN_PATH">Back to sign in</a></p>
</template>
