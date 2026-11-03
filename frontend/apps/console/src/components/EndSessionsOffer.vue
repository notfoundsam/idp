<script setup lang="ts">
import { useConsole } from '../context'
import { SESSIONS, useChange } from '../queries'
import { useAccountStore } from '../store'
import ProblemMessage from './ProblemMessage.vue'

const { client } = useConsole()
const store = useAccountStore()

const end = useChange(() => client.endOtherSessions(), [SESSIONS], () => {
  store.offerToEndSessions = false
  store.notice = 'Your other sessions are ended.'
})
</script>

<template>
  <aside v-if="store.offerToEndSessions" data-testid="end-sessions-offer">
    <p>Your sign-in methods changed. If someone else may have used your account, sign out everywhere else too.</p>
    <ProblemMessage :error="end.error.value" />
    <button type="button" :disabled="end.isPending.value" @click="end.mutate(undefined)">Sign out everywhere else</button>
    <button type="button" @click="store.offerToEndSessions = false">Not now</button>
  </aside>
  <p v-if="store.notice" role="status" data-testid="notice">{{ store.notice }}</p>
</template>
