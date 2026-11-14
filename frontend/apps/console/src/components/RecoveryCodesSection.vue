<script setup lang="ts">
import type { Factors } from '@idp/api-client'
import { computed } from 'vue'
import { useConsole } from '../context'
import { FACTORS, useChange } from '../queries'
import { useAccountStore } from '../store'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{ factors: Factors }>()

const { client } = useConsole()
const store = useAccountStore()
const secondFactor = computed(() => props.factors.totp || props.factors.passkeys.length > 0)

const renew = useChange(() => client.renewRecoveryCodes(), [FACTORS], (result) => store.factorChanged(result.recoveryCodes))
</script>

<template>
  <section aria-labelledby="recovery-heading">
    <h2 id="recovery-heading">Recovery codes</h2>
    <template v-if="secondFactor">
      <p data-testid="recovery-codes-left">{{ factors.recoveryCodesLeft }} recovery codes left.</p>
      <ProblemMessage :error="renew.error.value" />
      <button type="button" :disabled="renew.isPending.value" @click="renew.mutate(undefined)">Get new recovery codes</button>
    </template>
    <p v-else>Recovery codes come with an authenticator app or a passkey.</p>
  </section>
</template>
