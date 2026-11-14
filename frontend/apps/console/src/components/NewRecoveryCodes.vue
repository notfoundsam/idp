<script setup lang="ts">
import { RecoveryCodeList } from '@idp/ui'
import { useAccountStore } from '../store'

const store = useAccountStore()
</script>

<template>
  <section v-if="store.recoveryCodes.length > 0" aria-labelledby="new-codes-heading">
    <h2 id="new-codes-heading">Your new recovery codes</h2>
    <RecoveryCodeList :codes="store.recoveryCodes" />
    <button type="button" @click="store.recoveryCodes = []">I have saved them</button>
  </section>
</template>
