<script setup lang="ts">
import type { Role } from '@idp/api-client'
import { useQueryClient } from '@tanstack/vue-query'
import { ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { dateOf } from '../../messages'
import { useChange } from '../../queries'
import {
  actorOf,
  ALREADY_ACCEPTED,
  BLOCK_REFUSED,
  hasStatus,
  journalOf,
  LAST_ADMIN,
  ROLES,
  roleOf,
  statusOf,
  UNBLOCK_REFUSED,
  useUserCard,
  USERS,
} from '../admin'
import ConfirmAction from '../ConfirmAction.vue'
import { useAdminStore } from '../store'

const props = defineProps<{ id: string }>()

const { admin } = useConsole()
const router = useRouter()
const store = useAdminStore()
const queries = useQueryClient()
const card =useUserCard(toRef(props, 'id'))
const notice = ref(store.take())
const role = ref<Role>('USER')

watch(
  () => card.data.value?.role,
  (current) => {
    if (current !== undefined) role.value = current
  },
  { immediate: true },
)

/** A change tells what it did and reads the card anew, its status and journal included. */
function change(run: (id: string) => Promise<unknown>, done: string) {
  return useChange(() => run(props.id), [USERS], () => {
    notice.value = done
  })
}

const resend = change((id) => admin.resendInvitation(id), 'A new invitation is on its way. The link of the one before no longer works.')
const changeRole = change((id) => admin.changeRole(id, role.value), 'The role is changed. Their sessions follow it on their next request.')
const block = change((id) => admin.blockUser(id), 'The user is blocked, and their sessions are ended.')
const unblock = change((id) => admin.unblockUser(id), 'The user can sign in again.')
const endSessions = change((id) => admin.endUserSessions(id), 'Their sessions are ended.')
const resetMfa = change(
  (id) => admin.resetMfa(id),
  'Their authenticator apps, passkeys and recovery codes are removed, and their sessions are ended. They set up new ones at their next sign-in.',
)
/** The card leaves the cache, so going back to it shows that the user is gone rather than what they were. */
const erase = useChange(() => admin.eraseUser(props.id), [], async () => {
  store.notice = `${card.data.value?.email ?? 'The user'} is erased.`
  await router.push({ name: 'users' })
  queries.removeQueries({ queryKey: [...USERS, props.id] })
})

function act(action: { mutate: (variables: undefined) => void }) {
  notice.value = null
  action.mutate(undefined)
}
</script>

<template>
  <RouterLink :to="{ name: 'users' }">Back to users</RouterLink>
  <p v-if="card.isPending.value && !card.error.value" role="status">Loading…</p>
  <p v-if="hasStatus(card.error.value, 404)" role="alert" data-testid="no-such-user">There is no such user. They may have been erased.</p>
  <ProblemMessage v-else :error="card.error.value" />
  <template v-if="card.data.value">
    <h1 data-testid="user-email">{{ card.data.value.email }}</h1>
    <p v-if="notice" role="status" data-testid="admin-notice">{{ notice }}</p>
    <dl>
      <dt>Name</dt>
      <dd>{{ card.data.value.name ?? 'Not given' }}</dd>
      <dt>Role</dt>
      <dd data-testid="user-role">{{ roleOf(card.data.value.role) }}</dd>
      <dt>Status</dt>
      <dd data-testid="user-status">{{ statusOf(card.data.value.status) }}</dd>
    </dl>

    <section aria-labelledby="access-heading">
      <h2 id="access-heading">Access</h2>
      <template v-if="card.data.value.status === 'INVITED'">
        <ProblemMessage :error="resend.error.value" :rejected="ALREADY_ACCEPTED" />
        <button type="button" :disabled="resend.isPending.value" @click="act(resend)">Send the invitation again</button>
      </template>
      <form data-testid="role-form" @submit.prevent="act(changeRole)">
        <label for="user-role-choice">Role</label>
        <select id="user-role-choice" v-model="role" name="role">
          <option v-for="option in ROLES" :key="option" :value="option">{{ roleOf(option) }}</option>
        </select>
        <ProblemMessage :error="changeRole.error.value" :rejected="LAST_ADMIN" />
        <button type="submit" :disabled="changeRole.isPending.value || role === card.data.value.role">Change role</button>
      </form>
      <template v-if="card.data.value.status === 'ACTIVE'">
        <ProblemMessage :error="block.error.value" :rejected="BLOCK_REFUSED" />
        <button type="button" :disabled="block.isPending.value" @click="act(block)">Block</button>
      </template>
      <template v-if="card.data.value.status === 'BLOCKED'">
        <ProblemMessage :error="unblock.error.value" :rejected="UNBLOCK_REFUSED" />
        <button type="button" :disabled="unblock.isPending.value" @click="act(unblock)">Unblock</button>
      </template>
      <ProblemMessage :error="endSessions.error.value" />
      <button type="button" :disabled="endSessions.isPending.value" @click="act(endSessions)">End their sessions</button>
    </section>

    <section aria-labelledby="factors-heading">
      <h2 id="factors-heading">Lost factors</h2>
      <p>If the user lost their authenticator app or passkeys, remove all of them, so they set up new ones at their next sign-in.</p>
      <ProblemMessage :error="resetMfa.error.value" />
      <ConfirmAction
        label="Reset MFA"
        :question="`Remove every authenticator app, passkey and recovery code of ${card.data.value.email}, and end their sessions?`"
        confirm="Remove their factors"
        :busy="resetMfa.isPending.value"
        @confirmed="act(resetMfa)"
      />
    </section>

    <section aria-labelledby="services-heading">
      <h2 id="services-heading">Services</h2>
      <ul v-if="card.data.value.serviceProviders.length > 0">
        <li v-for="provider in card.data.value.serviceProviders" :key="provider.id" data-testid="service-provider">
          <RouterLink :to="{ name: 'service-provider', params: { id: provider.id } }">{{ provider.name }}</RouterLink>
          <small>{{ provider.entityId }}</small>
        </li>
      </ul>
      <p v-else data-testid="no-service-providers">The user is assigned to no service.</p>
    </section>

    <section aria-labelledby="events-heading">
      <h2 id="events-heading">Recent events</h2>
      <ol v-if="card.data.value.events.length > 0">
        <li v-for="event in card.data.value.events" :key="event.id" data-testid="user-event">
          <span>{{ dateOf(event.occurredAt) }}</span>: <code>{{ event.action }}</code> by {{ actorOf(event, card.data.value.id) }}
        </li>
      </ol>
      <p v-else>Nothing yet.</p>
      <RouterLink :to="journalOf({ type: 'USER', id: card.data.value.id })" data-testid="user-journal">
        Journal of this user, with the addresses and details
      </RouterLink>
    </section>

    <section aria-labelledby="erase-heading">
      <h2 id="erase-heading">Erase</h2>
      <p>Erasing removes the user with their data and sessions. It cannot be undone.</p>
      <ProblemMessage :error="erase.error.value" :rejected="LAST_ADMIN" />
      <ConfirmAction
        label="Erase user"
        :question="`Erase ${card.data.value.email} for good?`"
        confirm="Erase for good"
        :busy="erase.isPending.value"
        @confirmed="act(erase)"
      />
    </section>
  </template>
</template>
