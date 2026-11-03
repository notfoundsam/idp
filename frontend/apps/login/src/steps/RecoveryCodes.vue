<script setup lang="ts">
import type { Login } from '../login'
import ProblemMessage from './ProblemMessage.vue'

defineProps<{ login: Login }>()
</script>

<template>
  <section data-step="RECOVERY_CODES">
    <p>Save these recovery codes somewhere safe. Each one signs you in once if you lose your second factor. They are shown only now.</p>
    <ol data-testid="recovery-codes">
      <li v-for="code in login.state.recoveryCodes" :key="code">
        <code>{{ code }}</code>
      </li>
    </ol>
    <ProblemMessage :login="login" />
    <button type="button" :disabled="login.state.busy" @click="login.codesSaved()">I have saved them</button>
  </section>
</template>
