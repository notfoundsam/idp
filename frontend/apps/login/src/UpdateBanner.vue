<script setup lang="ts">
import type { Release } from '@idp/api-client'

defineProps<{
  release: Release
  reloadPage: () => void
}>()
</script>

<template>
  <aside v-if="release.newer" class="update" role="status" data-testid="update-banner">
    <p v-if="release.required">This page is out of date. Reload it to go on.</p>
    <p v-else>A new version is available. It loads when you go on to the next step.</p>
    <button v-if="release.required" type="button" @click="reloadPage()">Reload</button>
  </aside>
</template>
