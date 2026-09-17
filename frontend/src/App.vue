<script setup lang="ts">
import { onMounted, ref } from 'vue'

const health = ref<string>('checking...')

onMounted(async () => {
  try {
    const res = await fetch('/api/health')
    health.value = res.ok ? JSON.stringify(await res.json()) : `HTTP ${res.status}`
  } catch (e) {
    health.value = `unreachable: ${(e as Error).message}`
  }
})
</script>

<template>
  <main>
    <h1>AWS Marketplace Billing Mock</h1>
    <p>Backend health: <code data-testid="health">{{ health }}</code></p>
  </main>
</template>
