<script setup lang="ts">
import { needsReauthentication } from '@idp/api-client'
import { REAUTHENTICATION_PATH } from '../context'
import { messageOf } from '../messages'

defineProps<{
  error: unknown
  rejected?: string
}>()
</script>

<template>
  <p v-if="error" role="alert">
    {{ messageOf(error, rejected) }}
    <a v-if="needsReauthentication(error)" :href="REAUTHENTICATION_PATH">Sign in again</a>
  </p>
</template>
