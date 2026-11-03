<script setup lang="ts">
import { useAccountStore } from '../store'

const store = useAccountStore()
</script>

<template>
  <section v-if="store.recoveryCodes.length > 0" aria-labelledby="new-codes-heading">
    <h2 id="new-codes-heading">Your new recovery codes</h2>
    <p>Save these recovery codes somewhere safe. Each one signs you in once if you lose your second factor. They are shown only now.</p>
    <ol data-testid="recovery-codes">
      <li v-for="code in store.recoveryCodes" :key="code">
        <code>{{ code }}</code>
      </li>
    </ol>
    <button type="button" @click="store.recoveryCodes = []">I have saved them</button>
  </section>
</template>
