<script setup lang="ts">
import { ref } from 'vue'
import type { Login } from '../login'
import { FORGOT_PASSWORD_PATH } from '../pages'
import ProblemMessage from './ProblemMessage.vue'

const props = defineProps<{ login: Login }>()

const password = ref('')

function next() {
  const entered = password.value
  return props.login.submit((id) => props.login.client.submitPassword(id, entered))
}
</script>

<template>
  <form data-step="PASSWORD" @submit.prevent="next">
    <input v-if="login.state.login" type="text" name="username" autocomplete="username" :value="login.state.login" hidden readonly />
    <label for="password">Password</label>
    <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required />
    <ProblemMessage :state="login.state" />
    <button type="submit" :disabled="login.state.busy">Sign in</button>
  </form>
  <p><a :href="FORGOT_PASSWORD_PATH">Forgot your password?</a></p>
</template>
