<script setup lang="ts">
import { LOGIN_PATH } from '../context'
import { useLogout } from '../logout'
import { useAccountStore } from '../store'

const store = useAccountStore()
const { logout, busy } = useLogout()
</script>

<template>
  <main>
    <h1>Signed out</h1>
    <section v-if="store.signedOut === 'unconfirmed'" data-testid="signed-out-unconfirmed">
      <p role="alert">
        This page has forgotten your account, but the server did not confirm the end of your session. Sign out again once you are
        back online, or close the browser.
      </p>
      <button type="button" :disabled="busy" @click="logout">Sign out again</button>
    </section>
    <p v-else role="status" data-testid="signed-out">You are signed out.</p>
    <a :href="LOGIN_PATH">Sign in</a>
  </main>
</template>
