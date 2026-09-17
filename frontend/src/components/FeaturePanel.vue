<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { api } from '../api/endpoints'
import type { RunResult, SubscriptionView } from '../api/types'
import { useAsync } from '../composables/useAsync'
import { formatDateTime, formatNumber, reasonLabel } from '../format'
import AppBadge from './AppBadge.vue'
import ErrorNotice from './ErrorNotice.vue'

const props = defineProps<{ subscription: SubscriptionView }>()
const emit = defineEmits<{ changed: [] }>()

const dataGb = ref(2)
const lastKey = ref<string | null>(null)
const lastDataGb = ref<number | null>(null)
const call = useAsync((gb: number, key: string) => api.runAnalysis(gb, key))

const hasDataDimension = computed(() => props.subscription.product?.dimensions.some((d) => d.key === 'data_gb') ?? false)
const result = computed<RunResult | null>(() => call.data.value)

// 고객이 바뀌면 이전 결과를, 구독 상태가 바뀌면 이전 오류 안내를 지운다
watch(
  () => props.subscription.customerId,
  () => {
    call.reset()
    lastKey.value = null
  },
)
watch(
  () => props.subscription.status,
  () => {
    call.error.value = null
  },
)

function dimensionName(key: string): string {
  return props.subscription.product?.dimensions.find((d) => d.key === key)?.name ?? key
}

async function run(resend: boolean) {
  const key = resend && lastKey.value ? lastKey.value : crypto.randomUUID()
  const gb = resend && lastDataGb.value !== null ? lastDataGb.value : dataGb.value
  lastKey.value = key
  lastDataGb.value = gb
  const res = await call.run(gb, key)
  if (res) emit('changed')
}
</script>

<template>
  <section class="card" data-testid="feature-panel">
    <div class="card-head">
      <h2><span class="step">② 권한 확인 → ③ 사용량 발생</span>보호 기능</h2>
    </div>

    <div v-if="subscription.canUseFeatures" class="notice ok">
      <span class="icon" aria-hidden="true">●</span>
      <span>이 고객은 AI 분석 기능을 사용할 수 있습니다. 실행할 때마다 사용량이 기록됩니다.</span>
    </div>
    <div v-else class="notice warn" data-testid="access-locked">
      <span class="icon" aria-hidden="true">!</span>
      <span>
        <strong>이용 권한 없음</strong> — {{ reasonLabel[subscription.reason] }}.
        화면에서 막는 것과 별개로, 실행하면 서버가 직접 권한을 검사해 거부합니다.
      </span>
    </div>

    <div class="controls">
      <label v-if="hasDataDimension" class="field">
        처리할 데이터량 (GB)
        <input v-model.number="dataGb" type="number" min="1" max="100" data-testid="data-gb" />
      </label>
      <div class="button-row">
        <button type="button" class="primary" :disabled="call.loading.value" data-testid="run-analysis" @click="run(false)">
          {{ subscription.canUseFeatures ? 'AI 분석 실행' : 'AI 분석 실행 시도' }}
        </button>
        <button
          type="button"
          :disabled="call.loading.value || !lastKey"
          title="마지막 요청과 같은 Idempotency-Key로 다시 보냅니다"
          @click="run(true)"
        >
          같은 요청 재전송
        </button>
      </div>
    </div>

    <ErrorNotice v-if="call.error.value" :error="call.error.value" />

    <div v-if="result" class="result" data-testid="run-result">
      <div class="button-row">
        <strong>실행 결과</strong>
        <span class="faint">{{ formatDateTime(result.recordedAt) }}</span>
        <AppBadge v-if="result.replayed" tone="info" label="재전송 — 중복 기록 안 함" />
        <AppBadge v-if="result.overage" tone="warning" label="계약 수량 초과분 과금" />
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>차원</th>
              <th class="num">사용량</th>
              <th class="num">계약 차감</th>
              <th class="num">미터링 대상</th>
              <th class="num">남은 계약 수량</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="line in result.usage" :key="line.dimension">
              <td>{{ dimensionName(line.dimension) }}</td>
              <td class="num">{{ formatNumber(line.quantity) }}</td>
              <td class="num">{{ formatNumber(line.includedQuantity) }}</td>
              <td class="num">{{ formatNumber(line.meteredQuantity) }}</td>
              <td class="num">{{ formatNumber(line.includedRemaining) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="faint">Idempotency-Key: <span class="mono">{{ lastKey }}</span></p>
    </div>
  </section>
</template>

<style scoped>
.card {
  display: grid;
  gap: 12px;
  align-content: start;
}
.card-head {
  margin-bottom: 0;
}
.controls {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
}
.controls input {
  width: 120px;
}
.result {
  display: grid;
  gap: 8px;
  border-top: 1px dashed var(--border);
  padding-top: 10px;
}
</style>
