<script setup lang="ts">
import { PASSWORD_MIN_LENGTH, PASSWORD_RULES } from './password'

const password = defineModel<string>({ required: true })
</script>

<template>
  <label for="new-password">New password</label>
  <input
    id="new-password"
    v-model="password"
    name="new-password"
    type="password"
    autocomplete="new-password"
    :minlength="PASSWORD_MIN_LENGTH"
    aria-describedby="password-rules"
    required
  />
  <p id="password-rules">{{ PASSWORD_RULES }}</p>
</template>
