<script setup lang="ts">
import { computed } from 'vue'
import { qrModules } from './qr'

const props = defineProps<{
  value: string
  label: string
}>()

const code = computed(() => qrModules(props.value))
</script>

<template>
  <svg
    :viewBox="`0 0 ${code.size} ${code.size}`"
    width="200"
    height="200"
    role="img"
    :aria-label="label"
    shape-rendering="crispEdges"
    data-testid="qr-code"
  >
    <rect :width="code.size" :height="code.size" fill="#fff" />
    <path :d="code.path" fill="#000" />
  </svg>
</template>
