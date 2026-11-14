<script setup lang="ts">
import { PRECONDITION_FAILED, type VersionedPolicy } from '@idp/api-client'
import { reactive } from 'vue'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { useConsole } from '../../context'
import { ABSOLUTE_MAX_MINUTES, hasStatus, IDLE_MINUTES, POLICY, TIMEOUTS_REFUSED, usePolicy, useVersionedForm } from '../admin'

const { admin } = useConsole()
const policy = usePolicy()
const form = reactive({ mfaForUsers: true, passkeyRequired: false, idleMinutes: 30, absoluteMinutes: 720 })

function fill(read: VersionedPolicy) {
  form.mfaForUsers = read.policy.mfaForUsers
  form.passkeyRequired = read.policy.passkeyRequired
  form.idleMinutes = read.policy.sessionIdleSeconds / 60
  form.absoluteMinutes = read.policy.sessionAbsoluteSeconds / 60
}

const { version, saved, save, submit, reload } = useVersionedForm(
  policy,
  fill,
  (read) =>
    admin.savePolicy(
      {
        mfaForUsers: form.mfaForUsers,
        passkeyRequired: form.passkeyRequired,
        sessionIdleSeconds: Math.round(form.idleMinutes * 60),
        sessionAbsoluteSeconds: Math.round(form.absoluteMinutes * 60),
      },
      read,
    ),
  () => POLICY,
)
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
      <input id="idle-minutes" v-model.number="form.idleMinutes" name="idle-minutes" type="number" :min="IDLE_MINUTES.min" :max="IDLE_MINUTES.max" step="any" required />
      <label for="absolute-minutes">Session lifetime, minutes</label>
      <input
        id="absolute-minutes"
        v-model.number="form.absoluteMinutes"
        name="absolute-minutes"
        type="number"
        :min="IDLE_MINUTES.min"
        :max="ABSOLUTE_MAX_MINUTES"
        step="any"
        required
      />
      <p>A shorter timeout reaches the sessions already open on their next request.</p>
    </fieldset>
    <div v-if="hasStatus(save.error.value, PRECONDITION_FAILED)" role="alert" data-testid="policy-conflict">
      <p>Someone else saved the policy after you opened it. Load their version, then make your change again.</p>
      <button type="button" :disabled="policy.isFetching.value" @click="reload">Load the current policy</button>
    </div>
    <ProblemMessage v-else :error="save.error.value" :rejected="TIMEOUTS_REFUSED" />
    <p v-if="saved" role="status" data-testid="policy-saved">The policy is saved.</p>
    <button type="submit" :disabled="save.isPending.value">Save policy</button>
  </form>
</template>
