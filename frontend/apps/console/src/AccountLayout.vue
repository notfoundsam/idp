<script setup lang="ts">
import { useLogout } from './logout'
import { useIsAdmin, useSessions } from './queries'

const sessions = useSessions()
const isAdmin = useIsAdmin()
const { logout, busy } = useLogout()
</script>

<template>
  <header>
    <nav aria-label="Account">
      <RouterLink :to="{ name: 'security' }">Sign-in methods</RouterLink>
      <RouterLink :to="{ name: 'sessions' }">Sessions</RouterLink>
    </nav>
    <nav v-if="isAdmin" aria-label="Organisation" data-testid="admin-nav">
      <RouterLink :to="{ name: 'users' }">Users</RouterLink>
      <RouterLink :to="{ name: 'policy' }">Policy</RouterLink>
    </nav>
    <p v-if="sessions.data.value" data-testid="account-email">{{ sessions.data.value.email }}</p>
    <button type="button" :disabled="busy" @click="logout">Sign out</button>
  </header>
  <main>
    <RouterView />
  </main>
</template>
