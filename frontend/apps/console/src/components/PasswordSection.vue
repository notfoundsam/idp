<script setup lang="ts">
import { NewPassword } from '@idp/ui'
import { ref } from 'vue'
import { useConsole } from '../context'
import { PASSWORD_REFUSED } from '../messages'
import { SESSIONS, useChange } from '../queries'
import ProblemMessage from './ProblemMessage.vue'

const { client } = useConsole()
const current = ref('')
const password = ref('')
const endOtherSessions = ref(false)
const changed = ref(false)

const change = useChange(
  () => client.changePassword({ currentPassword: current.value, newPassword: password.value, endOtherSessions: endOtherSessions.value }),
  [SESSIONS],
  () => {
    current.value = ''
    password.value = ''
    endOtherSessions.value = false
    changed.value = true
  },
)

function submit() {
  changed.value = false
  change.mutate(undefined)
}
</script>

<template>
  <section aria-labelledby="password-heading">
    <h2 id="password-heading">Password</h2>
    <form @submit.prevent="submit">
      <label for="current-password">Current password</label>
      <input id="current-password" v-model="current" name="current-password" type="password" autocomplete="current-password" required />
      <NewPassword v-model="password" />
      <label>
        <input v-model="endOtherSessions" name="end-other-sessions" type="checkbox" />
        Sign out everywhere else
      </label>
      <ProblemMessage :error="change.error.value" :rejected="PASSWORD_REFUSED" />
      <p v-if="changed" role="status" data-testid="password-changed">Your password is changed.</p>
      <button type="submit" :disabled="change.isPending.value">Change password</button>
    </form>
  </section>
</template>
