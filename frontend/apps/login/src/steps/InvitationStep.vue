<script setup lang="ts">
import { ref } from 'vue'
import type { Login } from '../login'
import { PASSWORD_LINK_REFUSED } from '../messages'
import { NewPassword } from '@idp/ui'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{ login: Login }>()

const password = ref('')

function accept() {
  return props.login.acceptInvitation(password.value)
}
</script>

<template>
  <form data-step="INVITATION" @submit.prevent="accept">
    <p>Choose a password to accept the invitation.</p>
    <NewPassword v-model="password" />
    <ProblemMessage :state="login.state" :rejected="PASSWORD_LINK_REFUSED" />
    <button type="submit" :disabled="login.state.busy">Set password</button>
  </form>
</template>
