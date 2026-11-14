<script setup lang="ts">
import type { Client } from '@idp/api-client'
import { LINK_REFUSED, messageOf } from '../messages'
import { LOGIN_PATH } from '../pages'
import { createRecovery } from '../recovery'
import ProblemMessage from '../steps/ProblemMessage.vue'

const props = defineProps<{ client: Client; token: string | null }>()

const recovery = createRecovery()

function confirm(token: string) {
  return recovery.send(() => props.client.confirmEmailChange(token))
}
</script>

<template>
  <h1>Confirm your new email address</h1>
  <p v-if="token === null" role="alert">{{ messageOf('broken-link', null) }}</p>
  <template v-else-if="recovery.state.done">
    <p role="status" data-testid="email-changed">Your email address is changed. Sign in with the new one from now on.</p>
    <p><a :href="LOGIN_PATH">Sign in</a></p>
  </template>
  <form v-else @submit.prevent="confirm(token)">
    <p>Confirm that this address is yours, and it replaces the one you signed in with.</p>
    <ProblemMessage :state="recovery.state" :rejected="LINK_REFUSED" />
    <button type="submit" :disabled="recovery.state.busy">Confirm the address</button>
  </form>
</template>
