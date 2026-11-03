<script setup lang="ts">
import { useLogout } from './logout'
import { useSessions } from './queries'

const sessions = useSessions()
const { logout, busy } = useLogout()
</script>

<template>
  <header>
    <nav aria-label="Account">
      <RouterLink :to="{ name: 'security' }">Sign-in methods</RouterLink>
      <RouterLink :to="{ name: 'sessions' }">Sessions</RouterLink>
    </nav>
    <p v-if="sessions.data.value" data-testid="account-email">{{ sessions.data.value.email }}</p>
    <button type="button" :disabled="busy" @click="logout">Sign out</button>
  </header>
  <main>
    <RouterView />
  </main>
</template>
