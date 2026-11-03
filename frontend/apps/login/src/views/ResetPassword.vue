<script setup lang="ts">
import type { Client } from '@idp/api-client'
import { ref } from 'vue'
import { messageOf, PASSWORD_LINK_REFUSED } from '../messages'
import NewPassword from '../NewPassword.vue'
import { FORGOT_PASSWORD_PATH, LOGIN_PATH } from '../pages'
import { createRecovery } from '../recovery'
import ProblemMessage from '../steps/ProblemMessage.vue'

const props = defineProps<{ client: Client; token: string | null }>()

const recovery = createRecovery()
const password = ref('')

function send(token: string) {
  const entered = password.value
  return recovery.send(() => props.client.confirmPasswordReset(token, entered))
}
</script>

<template>
  <h1>Set a new password</h1>
  <p v-if="token === null" role="alert">{{ messageOf('broken-link', null) }}</p>
  <template v-else-if="recovery.state.done">
    <p role="status" data-testid="password-changed">
      Your password is changed, and every device that was signed in is signed out. Sign in with the new password.
    </p>
    <p><a :href="LOGIN_PATH">Sign in</a></p>
  </template>
  <template v-else>
    <form @submit.prevent="send(token)">
      <NewPassword v-model="password" />
      <ProblemMessage :state="recovery.state" :rejected="PASSWORD_LINK_REFUSED" />
      <button type="submit" :disabled="recovery.state.busy">Set password</button>
    </form>
    <p><a :href="FORGOT_PASSWORD_PATH">Ask for a new link</a></p>
  </template>
</template>
