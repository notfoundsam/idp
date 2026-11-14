<script setup lang="ts">
defineProps<{ codes: readonly string[] }>()
</script>

<template>
  <p>Save these recovery codes somewhere safe. Each one signs you in once if you lose your second factor. They are shown only now.</p>
  <ol data-testid="recovery-codes">
    <li v-for="code in codes" :key="code">
      <code>{{ code }}</code>
    </li>
  </ol>
</template>
