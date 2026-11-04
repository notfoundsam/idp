<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { dateOf } from '../../messages'
import { useChange } from '../../queries'
import { hasStatus, NO_ROTATION, ROTATION_IN_PROGRESS, SIGNING_KEYS, useSigningKeys } from '../admin'
import ConfirmAction from '../ConfirmAction.vue'

const { admin, browser } = useConsole()
const queries = useQueryClient()
const keys = useSigningKeys()
const notice = ref<string | null>(null)
const metadata = computed(() => `https://${browser.host}/saml/metadata`)
const rotating = computed(() => keys.data.value?.some((it) => it.status === 'NEXT') ?? false)

const start = useChange(() => admin.startKeyRotation(), [SIGNING_KEYS], () => {
  notice.value = 'The next key is published in the metadata. Once every service has read it, complete the rotation.'
})
const complete = useChange(() => admin.completeKeyRotation(), [SIGNING_KEYS], () => {
  notice.value = 'The next key signs now, and the one before is deleted.'
})

/** A conflict means another admin started or completed a rotation meanwhile, so the page shows the keys as they are now. */
async function refused(error: unknown) {
  if (hasStatus(error, 409)) await queries.invalidateQueries({ queryKey: SIGNING_KEYS })
}

function startRotation() {
  notice.value = null
  complete.reset()
  start.mutate(undefined, { onError: refused })
}

function completeRotation() {
  notice.value = null
  start.reset()
  complete.mutate(undefined, { onError: refused })
}
</script>

<template>
  <h1>Signing keys</h1>
  <p>
    Your services check the SAML responses of your organisation with the certificates of its metadata at
    <a :href="metadata" data-testid="metadata-url">{{ metadata }}</a>.
  </p>
  <p v-if="notice" role="status" data-testid="admin-notice">{{ notice }}</p>
  <p v-if="hasStatus(start.error.value, 409)" role="alert">{{ ROTATION_IN_PROGRESS }}</p>
  <p v-else-if="hasStatus(complete.error.value, 409)" role="alert">{{ NO_ROTATION }}</p>
  <ProblemMessage :error="keys.error.value" />
  <p v-if="keys.isPending.value && !keys.error.value" role="status">Loading…</p>
  <template v-if="keys.data.value">
    <ul>
      <li v-for="key in keys.data.value" :key="key.id" data-testid="signing-key" :data-status="key.status">
        <strong>{{ key.status === 'ACTIVE' ? 'Signs now' : 'Next, published but signing nothing yet' }}</strong>:
        <code data-testid="key-fingerprint">{{ key.sha256Fingerprint }}</code>, created {{ dateOf(key.createdAt) }}, valid until
        {{ dateOf(key.notAfter) }}
      </li>
    </ul>

    <section v-if="rotating" aria-labelledby="complete-heading">
      <h2 id="complete-heading">Complete the rotation</h2>
      <p>
        Complete it once every service has the metadata with the next key. A service that has not read it fails every sign-in until it
        does.
      </p>
      <ProblemMessage v-if="!hasStatus(complete.error.value, 409)" :error="complete.error.value" />
      <ConfirmAction
        label="Complete rotation"
        question="Let the next key sign and delete the one signing now? A service without the next key fails every sign-in."
        confirm="Complete now"
        :busy="complete.isPending.value"
        @confirmed="completeRotation"
      />
    </section>
    <section v-else aria-labelledby="rotate-heading">
      <h2 id="rotate-heading">Rotate</h2>
      <p>
        A rotation publishes a next key beside the one signing now. It signs nothing until you complete the rotation, so your services
        have time to read it.
      </p>
      <ProblemMessage v-if="!hasStatus(start.error.value, 409)" :error="start.error.value" />
      <button type="button" :disabled="start.isPending.value" @click="startRotation">Start rotation</button>
    </section>
  </template>
</template>
