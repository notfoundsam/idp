<script setup lang="ts">
import { RecoveryCodeList } from '@idp/ui'
import type { Login } from '../login'
import ProblemMessage from './ProblemMessage.vue'

defineProps<{ login: Login }>()
</script>

<template>
  <section data-step="RECOVERY_CODES">
    <RecoveryCodeList :codes="login.state.recoveryCodes" />
    <ProblemMessage :state="login.state" />
    <button type="button" :disabled="login.state.busy" @click="login.codesSaved()">I have saved them</button>
  </section>
</template>
