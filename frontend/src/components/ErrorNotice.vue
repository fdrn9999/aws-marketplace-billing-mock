<script setup lang="ts">
import { computed } from 'vue'

import { describeError } from '../format'

const props = defineProps<{ error: unknown; retry?: () => void }>()

const info = computed(() => describeError(props.error))
</script>

<template>
  <div class="notice error" role="alert" data-testid="error-notice">
    <span class="icon" aria-hidden="true">✕</span>
    <div class="body">
      <p>
        <strong>{{ info.title }}</strong>
        <code v-if="info.code" class="code">{{ info.code }}</code>
      </p>
      <p v-if="info.hint" class="muted">{{ info.hint }}</p>
      <p v-if="info.requestId" class="faint">요청 ID: <span class="mono">{{ info.requestId }}</span></p>
    </div>
    <button v-if="props.retry" type="button" class="retry" @click="props.retry">다시 시도</button>
  </div>
</template>

<style scoped>
.body {
  flex: 1;
  display: grid;
  gap: 2px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.code {
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--surface);
}
.retry {
  flex: none;
}
</style>
