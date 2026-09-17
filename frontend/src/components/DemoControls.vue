<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api, simulator } from '../api/endpoints'
import type { FaultApi, FaultMode, FaultSnapshot, MarketplaceEventType, MeteringRunSummary, SubscriptionView } from '../api/types'
import { refreshClock } from '../demo'
import ErrorNotice from './ErrorNotice.vue'

const props = defineProps<{ subscription: SubscriptionView | null }>()
const emit = defineEmits<{ changed: [] }>()

const busy = ref(false)
const error = ref<unknown>(null)
const message = ref<string | null>(null)
const lastRun = ref<MeteringRunSummary | null>(null)
const faults = ref<FaultSnapshot | null>(null)
const faultApi = ref<FaultApi>('BATCH_METER_USAGE')
const faultMode = ref<FaultMode>('UNPROCESSED')
const faultRemaining = ref(1)

const faultApiLabel: Record<FaultApi, string> = {
  RESOLVE_CUSTOMER: 'ResolveCustomer',
  GET_ENTITLEMENTS: 'GetEntitlements',
  BATCH_METER_USAGE: 'BatchMeterUsage',
}
const faultModeLabel: Record<FaultMode, string> = {
  NONE: '해제',
  THROTTLE: 'ThrottlingException',
  INTERNAL_ERROR: 'InternalServiceError (500)',
  UNPROCESSED: 'UnprocessedRecords',
}

async function act(label: string, action: () => Promise<string | void>) {
  busy.value = true
  error.value = null
  message.value = null
  try {
    const result = await action()
    message.value = result || `${label} 완료`
    emit('changed')
  } catch (e) {
    error.value = e
  } finally {
    busy.value = false
    await refreshClock()
  }
}

function advance(value: string, label: string) {
  return act(`시계 ${label} 이동`, async () => {
    await api.advanceClock(value)
    return `시뮬레이션 시계를 ${label} 앞으로 옮겼습니다.`
  })
}

function runMetering() {
  return act('미터링 실행', async () => {
    const summary = await api.runMetering()
    lastRun.value = summary
    return `미터링 실행: 전송 ${summary.claimed}건 → 성공 ${summary.success}, 재시도 대기 ${summary.retryLater}, 실패 ${summary.failed}`
  })
}

function reset() {
  return act('초기화', async () => {
    await api.reset()
    lastRun.value = null
    await loadFaults()
    return '시계와 모든 데이터를 시드 상태로 되돌렸습니다.'
  })
}

function publish(type: MarketplaceEventType, label: string) {
  const arn = props.subscription?.licenseArn
  if (!arn) return
  return act(label, async () => {
    const result = await simulator.publishEvent(type, arn, type === 'ENTITLEMENT_UPDATED' ? 365 : undefined)
    return `${label} 이벤트 발행 — 앱 전달 ${result.delivered ? '성공' : '실패'}`
  })
}

async function loadFaults() {
  try {
    faults.value = await simulator.faults()
  } catch {
    faults.value = null
  }
}

function applyFault() {
  return act('장애 주입', async () => {
    const remaining = faultMode.value === 'NONE' ? 0 : faultRemaining.value
    faults.value = await simulator.setFault(faultApi.value, faultMode.value, remaining)
    return faultMode.value === 'NONE'
      ? `${faultApiLabel[faultApi.value]} 장애 주입을 해제했습니다.`
      : `${faultApiLabel[faultApi.value]}에 ${faultModeLabel[faultMode.value]}을(를) ${remaining < 0 ? '계속' : `${remaining}회`} 발생시킵니다.`
  })
}

onMounted(loadFaults)
</script>

<template>
  <section class="card" data-testid="demo-controls">
    <div class="card-head">
      <h2>데모 컨트롤</h2>
      <span class="faint">시연용 기능입니다 (app.demo-mode)</span>
    </div>

    <div class="group">
      <h3>시간 이동 → 미터링</h3>
      <p class="faint">미터링은 마감된 시간대만 보냅니다. 시계를 1시간 옮긴 뒤 실행하면 방금 발생한 사용량이 전송됩니다.</p>
      <div class="button-row">
        <button type="button" :disabled="busy" data-testid="advance-1h" @click="advance('1h', '1시간')">+1시간</button>
        <button type="button" :disabled="busy" @click="advance('1d', '1일')">+1일</button>
        <button type="button" :disabled="busy" @click="advance('7d', '7일')">+7일</button>
        <button type="button" :disabled="busy" @click="advance('31d', '31일')">+31일</button>
        <button type="button" class="primary" :disabled="busy" data-testid="run-metering" @click="runMetering">
          미터링 실행 (BatchMeterUsage)
        </button>
      </div>
      <dl v-if="lastRun" class="kv run-summary" data-testid="metering-summary">
        <dt>0 레코드 보충</dt>
        <dd>{{ lastRun.zeroRecordsCreated }}건</dd>
        <dt>전송 / 호출</dt>
        <dd>{{ lastRun.claimed }}건 / {{ lastRun.batches }}회</dd>
        <dt>결과</dt>
        <dd>
          성공 {{ lastRun.success }} · 중복 {{ lastRun.duplicate }} · 미구독 {{ lastRun.customerNotSubscribed }} ·
          미처리 {{ lastRun.unprocessed }} · 재시도 대기 {{ lastRun.retryLater }} · 실패 {{ lastRun.failed }}
        </dd>
        <template v-if="lastRun.errors.length">
          <dt>호출 오류</dt>
          <dd>{{ lastRun.errors.join(', ') }}</dd>
        </template>
      </dl>
    </div>

    <div class="group">
      <h3>AWS Marketplace 이벤트 (현재 고객)</h3>
      <div class="button-row">
        <button type="button" :disabled="busy || !subscription?.licenseArn" @click="publish('SUBSCRIPTION_STARTED', '구독 시작')">
          구독 시작
        </button>
        <button type="button" :disabled="busy || !subscription?.licenseArn" @click="publish('ENTITLEMENT_UPDATED', '계약 갱신(+365일)')">
          계약 갱신 (+365일)
        </button>
        <button
          type="button"
          class="danger"
          :disabled="busy || !subscription?.licenseArn"
          data-testid="cancel-subscription"
          @click="publish('SUBSCRIPTION_CANCELLED', '구독 해지')"
        >
          구독 해지
        </button>
      </div>
    </div>

    <div class="group">
      <h3>장애 주입 (Mock AWS)</h3>
      <div class="button-row">
        <select v-model="faultApi" aria-label="장애를 넣을 API">
          <option v-for="(label, key) in faultApiLabel" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="faultMode" aria-label="장애 종류">
          <option v-for="(label, key) in faultModeLabel" :key="key" :value="key" :disabled="key === 'UNPROCESSED' && faultApi !== 'BATCH_METER_USAGE'">
            {{ label }}
          </option>
        </select>
        <select v-model.number="faultRemaining" aria-label="적용 횟수" :disabled="faultMode === 'NONE'">
          <option :value="1">1회</option>
          <option :value="3">3회</option>
          <option :value="10">10회</option>
          <option :value="-1">해제할 때까지</option>
        </select>
        <button type="button" :disabled="busy" @click="applyFault">적용</button>
      </div>
      <p v-if="faults" class="faint">
        현재:
        <template v-for="(f, key) in faults" :key="key">
          <span class="fault">{{ faultApiLabel[key] }}={{ f.mode === 'NONE' ? '정상' : `${faultModeLabel[f.mode]}(${f.remaining < 0 ? '계속' : f.remaining + '회'})` }}</span>
        </template>
      </p>
    </div>

    <div class="group">
      <div class="button-row">
        <button type="button" class="danger" :disabled="busy" data-testid="reset-demo" @click="reset">데이터 초기화</button>
      </div>
    </div>

    <div v-if="message" class="notice ok" role="status" data-testid="demo-message">
      <span class="icon" aria-hidden="true">●</span><span>{{ message }}</span>
    </div>
    <ErrorNotice v-if="error" :error="error" />
  </section>
</template>

<style scoped>
.card {
  display: grid;
  gap: 14px;
  align-content: start;
}
.card-head {
  margin-bottom: 0;
}
.group {
  display: grid;
  gap: 6px;
}
.run-summary {
  font-size: 13px;
  background: var(--surface-2);
  border-radius: 8px;
  padding: 8px 10px;
}
.fault {
  margin-right: 10px;
  white-space: nowrap;
}
</style>
