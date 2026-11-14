<script setup lang="ts">
import { PRECONDITION_FAILED, type Role, type SpSettings, type UserStatus, type VersionedServiceProvider } from '@idp/api-client'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { computed, reactive, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { dateOf } from '../../format'
import { useChange } from '../../queries'
import {
  assignedUsersKey,
  ATTRIBUTES,
  attributeOf,
  hasStatus,
  journalOf,
  MAX_USERS_PER_CALL,
  NAME_ID_FORMATS,
  nameIdFormatOf,
  roleOf,
  SERVICE_PROVIDERS,
  SIGNATURE_ALGORITHMS,
  signatureAlgorithmOf,
  SIGNED_PARTS,
  signedPartOf,
  statusOf,
  useAssignedUsers,
  USER_GONE,
  USERS,
  useServiceProvider,
  useVersionedForm,
} from '../admin'
import ConfirmAction from '../ConfirmAction.vue'
import { useAdminStore } from '../store'

const props = defineProps<{ id: string }>()

const { admin } = useConsole()
const router = useRouter()
const store = useAdminStore()
const queries = useQueryClient()
const id = toRef(props, 'id')
const provider = useServiceProvider(id)
const assigned = useAssignedUsers(id)
const notice = ref(store.take())
const current = computed(() => provider.data.value?.serviceProvider)

const form = reactive<SpSettings>({ nameIdFormat: 'PERSISTENT', signedPart: 'ASSERTION', signatureAlgorithm: 'RSA_PSS_SHA256', attributes: [] })

function fill(read: VersionedServiceProvider) {
  const settings = read.serviceProvider.settings
  form.nameIdFormat = settings.nameIdFormat
  form.signedPart = settings.signedPart
  form.signatureAlgorithm = settings.signatureAlgorithm
  form.attributes = [...settings.attributes]
}

const { version, saved, save, submit, reload } = useVersionedForm(
  provider,
  fill,
  (read) =>
    admin.saveServiceProviderSettings(props.id, { ...form, attributes: ATTRIBUTES.filter((it) => form.attributes.includes(it)) }, read),
  () => [...SERVICE_PROVIDERS, props.id],
)

function submitSettings() {
  notice.value = null
  submit()
}

const usersOfProvider = () => [assignedUsersKey(props.id), USERS]

const revoking = ref<string[]>([])
const revoke = useChange(
  (ids: string[]) => admin.unassignUsers(props.id, ids),
  usersOfProvider,
  () => {
    notice.value = `${countOf(revoking.value.length)} can no longer sign in to it. A session already open at the service lasts as the service decides.`
    revoking.value = []
  },
)

interface Picked {
  id: string
  email: string
  role: Role
  status: UserStatus
}

const wanted = ref('')
const picked = ref<Picked[]>([])
const notFound = ref<string | null>(null)
const search = useMutation({
  mutationFn: (email: string) => admin.searchUsers(email),
  onSuccess: (users, email) => {
    const user = users[0]
    if (user === undefined) {
      notFound.value = email
      return
    }
    if (!picked.value.some((it) => it.id === user.id)) picked.value.push({ ...user, email })
    wanted.value = ''
  },
})

function find() {
  notFound.value = null
  search.mutate(wanted.value.trim())
}

function leaveOut(user: string) {
  picked.value = picked.value.filter((it) => it.id !== user)
}

const assign = useChange(
  (ids: string[]) => admin.assignUsers(props.id, ids),
  usersOfProvider,
  () => {
    notice.value = `${countOf(picked.value.length)} can sign in to it now.`
    picked.value = []
  },
)

function countOf(users: number): string {
  return users === 1 ? '1 user' : `${users} users`
}

function act<V>(action: { mutate: (variables: V) => void }, variables: V) {
  notice.value = null
  action.mutate(variables)
}

/** The card leaves the cache, so going back to it shows that the service is gone rather than what it was. */
const remove = useChange(() => admin.removeServiceProvider(props.id), [], async () => {
  store.notice = `${current.value?.name ?? 'The service'} is removed. Its users can no longer sign in to it.`
  await router.push({ name: 'service-providers' })
  queries.removeQueries({ queryKey: [...SERVICE_PROVIDERS, props.id] })
  await Promise.all([SERVICE_PROVIDERS, USERS].map((queryKey) => queries.invalidateQueries({ queryKey })))
})
</script>

<template>
  <RouterLink :to="{ name: 'service-providers' }">Back to services</RouterLink>
  <p v-if="provider.isPending.value && !provider.error.value" role="status">Loading…</p>
  <p v-if="hasStatus(provider.error.value, 404)" role="alert" data-testid="no-such-service-provider">
    There is no such service. It may have been removed.
  </p>
  <ProblemMessage v-else :error="provider.error.value" />
  <template v-if="current">
    <h1 data-testid="sp-name">{{ current.name }}</h1>
    <p v-if="notice" role="status" data-testid="admin-notice">{{ notice }}</p>
    <dl>
      <dt>EntityID</dt>
      <dd data-testid="sp-entity-id">{{ current.entityId }}</dd>
      <dt>Signs its requests</dt>
      <dd data-testid="sp-requests-signed">{{ current.authnRequestsSigned ? 'Yes, so an unsigned one is refused' : 'No' }}</dd>
      <dt>Where responses go</dt>
      <dd>
        <ul>
          <li v-for="endpoint in current.acs" :key="endpoint.index" data-testid="sp-acs">
            {{ endpoint.url }}<template v-if="endpoint.isDefault"> (default)</template>
          </li>
        </ul>
      </dd>
      <dt>Certificates of its requests</dt>
      <dd>
        <ul v-if="current.signingCertificates.length > 0">
          <li v-for="certificate in current.signingCertificates" :key="certificate.sha256Fingerprint" data-testid="sp-certificate">
            <code>{{ certificate.sha256Fingerprint }}</code>, valid until {{ dateOf(certificate.notAfter) }}
          </li>
        </ul>
        <span v-else>None in its metadata</span>
      </dd>
    </dl>
    <RouterLink :to="journalOf({ type: 'SERVICE_PROVIDER', id: current.id })" data-testid="sp-journal">Journal of this service</RouterLink>

    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form v-if="version !== null" data-testid="settings-form" @submit.prevent="submitSettings">
        <label for="sp-name-id">NameID</label>
        <select id="sp-name-id" v-model="form.nameIdFormat" name="name-id">
          <option v-for="option in NAME_ID_FORMATS" :key="option" :value="option">{{ nameIdFormatOf(option) }}</option>
        </select>
        <label for="sp-signed-part">Signed part</label>
        <select id="sp-signed-part" v-model="form.signedPart" name="signed-part">
          <option v-for="option in SIGNED_PARTS" :key="option" :value="option">{{ signedPartOf(option) }}</option>
        </select>
        <label for="sp-algorithm">Signature algorithm</label>
        <select id="sp-algorithm" v-model="form.signatureAlgorithm" name="algorithm">
          <option v-for="option in SIGNATURE_ALGORITHMS" :key="option" :value="option">{{ signatureAlgorithmOf(option) }}</option>
        </select>
        <fieldset>
          <legend>Attributes the service receives</legend>
          <label v-for="attribute in ATTRIBUTES" :key="attribute">
            <input v-model="form.attributes" type="checkbox" name="attributes" :value="attribute" />
            {{ attributeOf(attribute) }}
          </label>
        </fieldset>
        <div v-if="hasStatus(save.error.value, PRECONDITION_FAILED)" role="alert" data-testid="settings-conflict">
          <p>Someone else saved the settings of this service after you opened them. Load their version, then make your change again.</p>
          <button type="button" :disabled="provider.isFetching.value" @click="reload">Load the current settings</button>
        </div>
        <ProblemMessage v-else :error="save.error.value" />
        <p v-if="saved" role="status" data-testid="settings-saved">The settings are saved.</p>
        <button type="submit" :disabled="save.isPending.value">Save settings</button>
      </form>
    </section>

    <section aria-labelledby="assigned-heading">
      <h2 id="assigned-heading">Users who sign in to it</h2>
      <ProblemMessage :error="assigned.error.value" />
      <template v-if="assigned.data.value">
        <form v-if="assigned.data.value.length > 0" data-testid="revoke-form" @submit.prevent="act(revoke, revoking)">
          <ul>
            <li v-for="user in assigned.data.value" :key="user.id" data-testid="assigned-user">
              <input :id="`revoke-${user.id}`" v-model="revoking" type="checkbox" name="revoke" :value="user.id" />
              <label :for="`revoke-${user.id}`">User {{ user.id }}</label>,
              <span>{{ roleOf(user.role) }}</span>,
              <span>{{ statusOf(user.status) }}</span>
              <RouterLink :to="{ name: 'user', params: { id: user.id } }">Card</RouterLink>
            </li>
          </ul>
          <ProblemMessage :error="revoke.error.value" />
          <button type="submit" :disabled="revoke.isPending.value || revoking.length === 0">Revoke selected</button>
        </form>
        <p v-else data-testid="no-assigned-users">Nobody is assigned, so nobody signs in to it.</p>
      </template>
    </section>

    <section aria-labelledby="assign-heading">
      <h2 id="assign-heading">Assign users</h2>
      <p>Find each user by the whole address and add them to the selection, then assign everyone selected at once.</p>
      <form data-testid="pick-form" @submit.prevent="find">
        <label for="pick-email">Email address of a user</label>
        <input id="pick-email" v-model="wanted" name="pick-email" type="email" autocomplete="off" required />
        <button type="submit" :disabled="search.isPending.value || picked.length >= MAX_USERS_PER_CALL">Add</button>
      </form>
      <ProblemMessage :error="search.error.value" />
      <p v-if="notFound !== null" data-testid="pick-no-user">No user has the address {{ notFound }}.</p>
      <template v-if="picked.length > 0">
        <ul>
          <li v-for="user in picked" :key="user.id" data-testid="picked-user">
            <span>{{ user.email }}</span>, <span>{{ roleOf(user.role) }}</span>, <span>{{ statusOf(user.status) }}</span>
            <button type="button" @click="leaveOut(user.id)">Leave out</button>
          </li>
        </ul>
        <p v-if="hasStatus(assign.error.value, 404)" role="alert">{{ USER_GONE }}</p>
        <ProblemMessage v-else :error="assign.error.value" />
        <button type="button" :disabled="assign.isPending.value" @click="act(assign, picked.map((it) => it.id))">Assign selected</button>
      </template>
    </section>

    <section aria-labelledby="remove-heading">
      <h2 id="remove-heading">Remove</h2>
      <p>Removing the service removes every assignment to it, so its users can no longer sign in to it.</p>
      <ProblemMessage :error="remove.error.value" />
      <ConfirmAction
        label="Remove service"
        :question="`Remove ${current.name}? Its users can no longer sign in to it.`"
        confirm="Remove for good"
        :busy="remove.isPending.value"
        @confirmed="act(remove, undefined)"
      />
    </section>
  </template>
</template>
