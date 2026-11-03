<script setup lang="ts">
import type { Release } from '@idp/api-client'
import { UpdateBanner } from '@idp/ui'
import { defineAsyncComponent, onMounted } from 'vue'
import type { Login } from './login'
import { messageOf } from './messages'
import type { Page } from './pages'

const props = defineProps<{
  login: Login
  release: Release
  credentials: CredentialsContainer | null
  host: string
  reloadPage: () => void
  page?: Page
  token?: string | null
}>()

const IdentifyStep = defineAsyncComponent(() => import('./steps/IdentifyStep.vue'))
const PasswordStep = defineAsyncComponent(() => import('./steps/PasswordStep.vue'))
const SecondFactorStep = defineAsyncComponent(() => import('./steps/SecondFactorStep.vue'))
const EnrollMfaStep = defineAsyncComponent(() => import('./steps/EnrollMfaStep.vue'))
const RecoveryCodes = defineAsyncComponent(() => import('./steps/RecoveryCodes.vue'))
const InvitationStep = defineAsyncComponent(() => import('./steps/InvitationStep.vue'))
const ForgotPassword = defineAsyncComponent(() => import('./views/ForgotPassword.vue'))
const ResetPassword = defineAsyncComponent(() => import('./views/ResetPassword.vue'))
const ConfirmEmail = defineAsyncComponent(() => import('./views/ConfirmEmail.vue'))

const page = props.page ?? 'login'
const token = props.token ?? null
const state = props.login.state

onMounted(async () => {
  if (page === 'invite') props.login.beginInvitation(token)
  else if (page === 'login') await props.login.begin()
})
</script>

<template>
  <main>
    <UpdateBanner :release="release" :reload-page="reloadPage" />
    <ForgotPassword v-if="page === 'forgot-password'" :client="login.client" />
    <ResetPassword v-else-if="page === 'reset-password'" :client="login.client" :token="token" />
    <ConfirmEmail v-else-if="page === 'confirm-email'" :client="login.client" :token="token" />
    <template v-else>
      <h1>{{ page === 'invite' && state.phase === 'invitation' ? 'Accept your invitation' : 'Sign in' }}</h1>
      <p v-if="state.phase === 'loading' || state.phase === 'leaving'" role="status">Loading…</p>
      <InvitationStep v-else-if="state.phase === 'invitation'" :login="login" />
      <template v-else-if="state.phase === 'step' && state.flow">
        <IdentifyStep v-if="state.flow.step === 'IDENTIFY'" :login="login" :credentials="credentials" />
        <PasswordStep v-else-if="state.flow.step === 'PASSWORD'" :login="login" />
        <SecondFactorStep v-else-if="state.flow.step === 'SECOND_FACTOR'" :login="login" :credentials="credentials" />
        <EnrollMfaStep v-else-if="state.flow.step === 'ENROLL_MFA'" :login="login" :credentials="credentials" :host="host" />
      </template>
      <RecoveryCodes v-else-if="state.phase === 'recovery-codes'" :login="login" />
      <section v-else-if="state.phase === 'expired'">
        <p role="alert">This sign-in has expired. Go back to the application you came from, or start again here.</p>
        <a href="/login/">Start again</a>
      </section>
      <p v-else-if="state.phase === 'failed'" role="alert">
        {{ messageOf(state.problem ?? 'unexpected', state.retryAfter) }}
      </p>
    </template>
  </main>
</template>
