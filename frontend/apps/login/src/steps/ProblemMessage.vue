<script setup lang="ts">
import type { Problem } from '../login'
import { messageOf } from '../messages'

defineProps<{
  state: { problem: Problem | null; retryAfter: number | null }
  rejected?: string
}>()
</script>

<template>
  <p v-if="state.problem" role="alert">
    {{ state.problem === 'rejected' && rejected ? rejected : messageOf(state.problem, state.retryAfter) }}
  </p>
</template>
