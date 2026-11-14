<script setup lang="ts">
import type { FoundUser, Role } from '@idp/api-client'
import { useMutation } from '@tanstack/vue-query'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { useLogout } from '../../logout'
import { INVITATION_REFUSED, ROLES, roleOf, statusOf } from '../admin'
import ConfirmAction from '../ConfirmAction.vue'
import { useAdminStore } from '../store'

const { admin } = useConsole()
const router = useRouter()
const store = useAdminStore()
const { logout } = useLogout()
const notice = ref(store.take())

const wanted = ref('')
const found = ref<{ email: string; users: FoundUser[] } | null>(null)
const search = useMutation({
  mutationFn: (email: string) => admin.searchUsers(email),
  onSuccess: (users, email) => {
    found.value = { email, users }
  },
})

function find() {
  notice.value = null
  found.value = null
  search.mutate(wanted.value.trim())
}

const invitee = ref('')
const role = ref<Role>('USER')
const invite = useMutation({
  mutationFn: () => admin.inviteUser({ email: invitee.value.trim(), role: role.value }),
  onSuccess: async (invited) => {
    store.notice = `An invitation is on its way to ${invitee.value.trim()}.`
    await router.push({ name: 'user', params: { id: invited.id } })
  },
})

/** The session of this page ends too, so the page signs out the way the button at the top does. */
const endAll = useMutation({ mutationFn: () => admin.endAllSessions(), onSuccess: () => logout() })
</script>

<template>
  <h1>Users</h1>
  <p v-if="notice" role="status" data-testid="admin-notice">{{ notice }}</p>

  <section aria-labelledby="search-heading">
    <h2 id="search-heading">Find a user</h2>
    <p>Only the whole address finds a user, in any case. The organisation is never listed in full.</p>
    <form data-testid="search-form" @submit.prevent="find">
      <label for="search-email">Email address</label>
      <input id="search-email" v-model="wanted" name="search-email" type="email" autocomplete="off" required />
      <button type="submit" :disabled="search.isPending.value">Find</button>
    </form>
    <ProblemMessage :error="search.error.value" />
    <template v-if="found">
      <ul v-if="found.users.length > 0">
        <li v-for="user in found.users" :key="user.id" data-testid="found-user">
          <RouterLink :to="{ name: 'user', params: { id: user.id } }">{{ found.email }}</RouterLink>,
          <span>{{ roleOf(user.role) }}</span>,
          <span>{{ statusOf(user.status) }}</span>
        </li>
      </ul>
      <p v-else data-testid="no-user">No user has the address {{ found.email }}.</p>
    </template>
  </section>

  <section aria-labelledby="invite-heading">
    <h2 id="invite-heading">Invite a user</h2>
    <form data-testid="invite-form" @submit.prevent="invite.mutate(undefined)">
      <label for="invite-email">Email of the new user</label>
      <input id="invite-email" v-model="invitee" name="invite-email" type="email" autocomplete="off" required />
      <label for="invite-role">Role</label>
      <select id="invite-role" v-model="role" name="invite-role">
        <option v-for="option in ROLES" :key="option" :value="option">{{ roleOf(option) }}</option>
      </select>
      <ProblemMessage :error="invite.error.value" :rejected="INVITATION_REFUSED" />
      <button type="submit" :disabled="invite.isPending.value">Invite</button>
    </form>
  </section>

  <section aria-labelledby="everyone-heading">
    <h2 id="everyone-heading">Everyone's sessions</h2>
    <p>If an account of your organisation may be in the wrong hands, sign everyone out. You are signed out too.</p>
    <ProblemMessage :error="endAll.error.value" />
    <ConfirmAction
      label="Sign everyone out"
      question="Sign out every user of your organisation, you too?"
      confirm="Sign everyone out now"
      :busy="endAll.isPending.value"
      @confirmed="endAll.mutate(undefined)"
    />
  </section>
</template>
