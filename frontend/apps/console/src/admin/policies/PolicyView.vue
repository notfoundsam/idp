<script setup lang="ts">
import type { VersionedPolicy } from '@idp/api-client'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { reactive, ref, watch } from 'vue'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { isConflict, POLICY, TIMEOUTS_REFUSED, usePolicy } from '../admin'

const { admin } = useConsole()
const queries = useQueryClient()
const policy = usePolicy()
const form = reactive({ mfaForUsers: true, passkeyRequired: false, idleMinutes: 30, absoluteMinutes: 720 })
const version = ref<string | null>(null)
const saved = ref(false)

function fill(read: VersionedPolicy) {
  form.mfaForUsers = read.policy.mfaForUsers
  form.passkeyRequired = read.policy.passkeyRequired
  form.idleMinutes = read.policy.sessionIdleSeconds / 60
  form.absoluteMinutes = read.policy.sessionAbsoluteSeconds / 60
  version.value = read.version
}

/** A policy cached from an earlier visit waits for the fresh one, whose version a save would match. */
watch(
  () => [policy.data.value, policy.isFetching.value] as const,
  ([read, fetching]) => {
    if (read !== undefined && !fetching && version.value === null) fill(read)
  },
  { immediate: true },
)

const save = useMutation({
  mutationFn: (read: string) =>
    admin.savePolicy(
      {
        mfaForUsers: form.mfaForUsers,
        passkeyRequired: form.passkeyRequired,
        sessionIdleSeconds: Math.round(form.idleMinutes * 60),
        sessionAbsoluteSeconds: Math.round(form.absoluteMinutes * 60),
      },
      read,
    ),
  onSuccess: async (next) => {
    saved.value = true
    version.value = next
    await queries.invalidateQueries({ queryKey: POLICY })
  },
})

function submit() {
  saved.value = false
  if (version.value !== null) save.mutate(version.value)
}

/** The change of the other admin replaces what this form held, and the admin makes theirs again on top of it. */
async function reload() {
  const read = await policy.refetch()
  if (read.data === undefined) return
  fill(read.data)
  save.reset()
}
</script>

<template>
  <h1>Policy</h1>
  <ProblemMessage :error="policy.error.value" />
  <p v-if="policy.isPending.value && !policy.error.value" role="status">Loading…</p>
  <form v-if="version !== null" data-testid="policy-form" @submit.prevent="submit">
    <fieldset>
      <legend>Sign-in</legend>
      <label>
        <input v-model="form.mfaForUsers" name="mfa-for-users" type="checkbox" />
        Users sign in with a second factor
      </label>
      <p>Admins always sign in with a second factor, so the policy has no switch for them.</p>
      <label>
        <input v-model="form.passkeyRequired" name="passkey-required" type="checkbox" />
        Everyone signs in with a passkey
      </label>
    </fieldset>
    <fieldset>
      <legend>Sessions</legend>
      <label for="idle-minutes">Idle timeout, minutes</label>
      <input id="idle-minutes" v-model.number="form.idleMinutes" name="idle-minutes" type="number" min="5" max="60" step="any" required />
      <label for="absolute-minutes">Session lifetime, minutes</label>
      <input
        id="absolute-minutes"
        v-model.number="form.absoluteMinutes"
        name="absolute-minutes"
        type="number"
        min="5"
        max="1440"
        step="any"
        required
      />
      <p>A shorter timeout reaches the sessions already open on their next request.</p>
    </fieldset>
    <div v-if="isConflict(save.error.value)" role="alert" data-testid="policy-conflict">
      <p>Someone else saved the policy after you opened it. Load their version, then make your change again.</p>
      <button type="button" :disabled="policy.isFetching.value" @click="reload">Load the current policy</button>
    </div>
    <ProblemMessage v-else :error="save.error.value" :rejected="TIMEOUTS_REFUSED" />
    <p v-if="saved" role="status" data-testid="policy-saved">The policy is saved.</p>
    <button type="submit" :disabled="save.isPending.value">Save policy</button>
  </form>
</template>
