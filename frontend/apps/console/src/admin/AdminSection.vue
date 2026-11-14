<script setup lang="ts">
import ProblemMessage from '../components/ProblemMessage.vue'
import { useIsAdmin, useSessions } from '../queries'

const sessions = useSessions()
const isAdmin = useIsAdmin()
</script>

<template>
  <ProblemMessage :error="sessions.error.value" />
  <p v-if="sessions.isPending.value && !sessions.error.value" role="status">Loading…</p>
  <RouterView v-else-if="isAdmin" />
  <p v-else-if="sessions.data.value" data-testid="admins-only">These pages are for the admins of your organisation.</p>
</template>
