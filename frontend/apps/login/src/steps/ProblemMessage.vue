<script setup lang="ts">
import type { Login } from '../login'
import { messageOf } from '../messages'

defineProps<{ login: Login }>()
</script>

<template>
  <p v-if="login.state.problem" role="alert">{{ messageOf(login.state.problem, login.state.retryAfter) }}</p>
</template>
