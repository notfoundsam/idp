<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { useChange } from '../../queries'
import {
  ENTITY_ID_TAKEN,
  hasStatus,
  MAX_METADATA_BYTES,
  METADATA_REFUSED,
  METADATA_TOO_LARGE,
  SERVICE_PROVIDERS,
  useServiceProviders,
} from '../admin'
import { useAdminStore } from '../store'

const { admin } = useConsole()
const router = useRouter()
const store = useAdminStore()
const notice = ref(store.take())
const providers = useServiceProviders()

const name = ref('')
const metadata = ref<File | null>(null)
const tooLarge = ref(false)

function picked(event: Event) {
  metadata.value = (event.target as HTMLInputElement).files?.[0] ?? null
  tooLarge.value = false
}

const register = useChange(
  (upload: { name: string; file: File }) => admin.registerServiceProvider(upload.name, upload.file),
  [SERVICE_PROVIDERS],
  async (id) => {
    store.notice = `${name.value.trim()} is connected. Nobody is assigned to it yet, so nobody signs in to it.`
    await router.push({ name: 'service-provider', params: { id } })
  },
)

function submit() {
  notice.value = null
  register.reset()
  const file = metadata.value
  if (file === null) return
  tooLarge.value = file.size > MAX_METADATA_BYTES
  if (!tooLarge.value) register.mutate({ name: name.value.trim(), file })
}
</script>

<template>
  <h1>Services</h1>
  <p v-if="notice" role="status" data-testid="admin-notice">{{ notice }}</p>
  <p>The services your users sign in to through SAML. Only the users assigned to a service sign in to it.</p>

  <section aria-labelledby="providers-heading">
    <h2 id="providers-heading">Connected services</h2>
    <ProblemMessage :error="providers.error.value" />
    <p v-if="providers.isPending.value && !providers.error.value" role="status">Loading…</p>
    <template v-if="providers.data.value">
      <ul v-if="providers.data.value.length > 0">
        <li v-for="provider in providers.data.value" :key="provider.id" data-testid="service-provider-item">
          <RouterLink :to="{ name: 'service-provider', params: { id: provider.id } }">{{ provider.name }}</RouterLink>
          <small>{{ provider.entityId }}</small>
        </li>
      </ul>
      <p v-else data-testid="no-service-providers">No service is connected yet.</p>
    </template>
  </section>

  <section aria-labelledby="import-heading">
    <h2 id="import-heading">Connect a service</h2>
    <p>
      Upload the SAML metadata file the service gives you, at most 128 KiB. Only its content is read. The service starts with a persistent
      NameID, no attributes, and nobody assigned.
    </p>
    <form data-testid="import-form" @submit.prevent="submit">
      <label for="sp-name">Name</label>
      <input id="sp-name" v-model="name" name="sp-name" type="text" maxlength="200" autocomplete="off" required />
      <label for="sp-metadata">Metadata file</label>
      <input
        id="sp-metadata"
        name="sp-metadata"
        type="file"
        accept=".xml,application/samlmetadata+xml,application/xml,text/xml"
        required
        @change="picked"
      />
      <p v-if="tooLarge || hasStatus(register.error.value, 413)" role="alert">{{ METADATA_TOO_LARGE }}</p>
      <p v-else-if="hasStatus(register.error.value, 409)" role="alert">{{ ENTITY_ID_TAKEN }}</p>
      <ProblemMessage v-else :error="register.error.value" :rejected="METADATA_REFUSED" />
      <button type="submit" :disabled="register.isPending.value">Connect</button>
    </form>
  </section>
</template>
