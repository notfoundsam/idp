<script setup lang="ts">
import { UpdateBanner } from '@idp/ui'
import { useConsole } from './context'

const { release, browser } = useConsole()
</script>

<template>
  <UpdateBanner :release="release" :reload-page="browser.reload" next="page" />
  <RouterView />
</template>
