<script setup lang="ts">
import { ref, watch } from 'vue'

import { simulator } from '../api/endpoints'
import type { CallLogEntry } from '../api/types'
import { formatDateTime } from '../format'
import AppBadge from './AppBadge.vue'

// 앱이 Mock AWS API를 실제로 어떻게 호출했는지(요청/응답 원문)를 보여준다
const props = defineProps<{ licenseArn: string | null; refreshKey: number }>()

const entries = ref<CallLogEntry[]>([])
const failed = ref(false)

async function load() {
  try {
    entries.value = await simulator.calls(props.licenseArn, 15)
    failed.value = false
  } catch {
    failed.value = true
  }
}

watch(() => [props.licenseArn, props.refreshKey], load, { immediate: true })

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}
</script>

<template>
  <section class="card" data-testid="call-log">
    <div class="card-head">
      <h2>AWS API 호출 로그</h2>
      <button type="button" @click="load">새로고침</button>
    </div>
    <p class="faint">
      ResolveCustomer · GetEntitlements · BatchMeterUsage 호출의 요청/응답 원문입니다 (현재 고객 + 여러 고객이 묶인 배치).
    </p>
    <p v-if="failed" class="muted">호출 로그를 불러오지 못했습니다.</p>
    <p v-else-if="!entries.length" class="muted">아직 호출 기록이 없습니다.</p>
    <ul class="log">
      <li v-for="e in entries" :key="e.id">
        <details>
          <summary>
            <AppBadge :tone="e.httpStatus < 300 ? 'good' : 'critical'" :label="String(e.httpStatus)" />
            <strong class="api">{{ e.api }}</strong>
            <code v-if="e.errorType">{{ e.errorType }}</code>
            <span class="faint">{{ formatDateTime(e.at) }} · {{ e.durationMs }}ms</span>
          </summary>
          <div class="io">
            <div>
              <span class="faint">Request</span>
              <pre>{{ pretty(e.request) }}</pre>
            </div>
            <div>
              <span class="faint">Response</span>
              <pre>{{ pretty(e.response) }}</pre>
            </div>
          </div>
        </details>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.log {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: grid;
  gap: 4px;
}
summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 4px 0;
}
.api {
  font-size: 14px;
}
.io {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 4px 0 8px;
}
@media (max-width: 700px) {
  .io {
    grid-template-columns: minmax(0, 1fr);
  }
}
pre {
  max-height: 260px;
}
</style>
