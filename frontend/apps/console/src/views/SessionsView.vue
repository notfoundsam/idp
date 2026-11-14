<script setup lang="ts">
import { computed } from 'vue'
import ProblemMessage from '../components/ProblemMessage.vue'
import { useConsole } from '../context'
import { dateOf } from '../format'
import { SESSIONS, useChange, useSessions } from '../queries'

const { client } = useConsole()
const sessions = useSessions()
const others = computed(() => (sessions.data.value?.sessions ?? []).filter((it) => !it.current).length)

const endOne = useChange((id: string) => client.endSession(id), [SESSIONS])
const endOthers = useChange(() => client.endOtherSessions(), [SESSIONS])
</script>

<template>
  <h1>Sessions</h1>
  <p>These browsers are signed in to your account. Sign out of this one with the button at the top.</p>
  <ProblemMessage :error="sessions.error.value" />
  <p v-if="sessions.isPending.value && !sessions.error.value" role="status">Loading…</p>
  <ul v-if="sessions.data.value">
    <li v-for="session in sessions.data.value.sessions" :key="session.id" data-testid="session" :data-current="session.current">
      <strong v-if="session.current">This browser</strong>
      <span>{{ session.userAgent ?? 'Unknown browser' }}</span>,
      <span>from {{ session.ip ?? 'an unknown address' }}</span>,
      <span>signed in {{ dateOf(session.authenticatedAt) }}</span>,
      <span>last seen {{ dateOf(session.lastSeenAt) }}</span>
      <button
        v-if="!session.current"
        type="button"
        :disabled="endOne.isPending.value"
        @click="endOne.mutate(session.id)"
      >
        End session
      </button>
    </li>
  </ul>
  <ProblemMessage :error="endOne.error.value" />
  <ProblemMessage :error="endOthers.error.value" />
  <button v-if="others > 0" type="button" :disabled="endOthers.isPending.value" @click="endOthers.mutate(undefined)">
    Sign out everywhere else
  </button>
</template>
