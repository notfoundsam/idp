<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  label: string
  question: string
  confirm: string
  busy: boolean
}>()

const emit = defineEmits<{ confirmed: [] }>()
const asking = ref(false)

function confirmed() {
  asking.value = false
  emit('confirmed')
}
</script>

<template>
  <div v-if="asking" role="group" :aria-label="label" data-testid="confirmation">
    <p>{{ question }}</p>
    <button type="button" :disabled="busy" @click="confirmed">{{ confirm }}</button>
    <button type="button" @click="asking = false">Cancel</button>
  </div>
  <button v-else type="button" :disabled="busy" @click="asking = true">{{ label }}</button>
</template>
