<script setup lang="ts">
import { computed } from 'vue'
import EndSessionsOffer from '../components/EndSessionsOffer.vue'
import NewRecoveryCodes from '../components/NewRecoveryCodes.vue'
import PasskeysSection from '../components/PasskeysSection.vue'
import PasswordSection from '../components/PasswordSection.vue'
import ProblemMessage from '../components/ProblemMessage.vue'
import RecoveryCodesSection from '../components/RecoveryCodesSection.vue'
import TotpSection from '../components/TotpSection.vue'
import { useFactors, useSessions } from '../queries'

const factors = useFactors()
const sessions = useSessions()
const email = computed(() => sessions.data.value?.email ?? null)
</script>

<template>
  <h1>Sign-in methods</h1>
  <NewRecoveryCodes />
  <EndSessionsOffer />
  <PasswordSection />
  <ProblemMessage :error="factors.error.value" />
  <p v-if="factors.isPending.value && !factors.error.value" role="status">Loading…</p>
  <template v-if="factors.data.value">
    <TotpSection :factors="factors.data.value" :email="email" />
    <PasskeysSection :factors="factors.data.value" :email="email" />
    <RecoveryCodesSection :factors="factors.data.value" />
  </template>
</template>
