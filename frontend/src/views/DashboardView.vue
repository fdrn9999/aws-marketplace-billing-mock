<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'

import { api } from '../api/endpoints'
import type { BillingSummary, CustomerItem, SubscriptionView, UsageSummary } from '../api/types'
import AppBadge from '../components/AppBadge.vue'
import BillingCard from '../components/BillingCard.vue'
import CallLogPanel from '../components/CallLogPanel.vue'
import DemoControls from '../components/DemoControls.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import FeaturePanel from '../components/FeaturePanel.vue'
import SubscriptionCard from '../components/SubscriptionCard.vue'
import UsageCard from '../components/UsageCard.vue'
import { refreshClock } from '../demo'
import { pricingModelLabel, statusLabel } from '../format'
import { session } from '../session'
import { subscriptionTone } from '../status'

const customers = ref<CustomerItem[]>([])
const subscription = shallowRef<SubscriptionView | null>(null)
const usage = shallowRef<UsageSummary | null>(null)
const billing = shallowRef<BillingSummary | null>(null)
const loadError = shallowRef<unknown>(null)
const partialError = shallowRef<unknown>(null)
const loading = ref(false)
const refreshing = ref(false)
const refreshKey = ref(0)

const current = computed(() => customers.value.find((c) => c.customerId === session.customerId) ?? null)

async function loadCustomers() {
  try {
    customers.value = await api.customers()
    if (!customers.value.some((c) => c.customerId === session.customerId) && customers.value.length) {
      session.customerId = customers.value[0].customerId
    }
  } catch (e) {
    loadError.value = e
  }
}

/** ①~⑤ 흐름에 필요한 데이터를 한 번에 다시 읽는다 */
async function loadAll() {
  loading.value = true
  loadError.value = null
  partialError.value = null
  const [s, u, b] = await Promise.allSettled([api.subscription(), api.usage(), api.billing()])
  if (s.status === 'fulfilled') {
    subscription.value = s.value
  } else {
    subscription.value = null
    loadError.value = s.reason
  }
  usage.value = u.status === 'fulfilled' ? u.value : null
  billing.value = b.status === 'fulfilled' ? b.value : null
  if (s.status === 'fulfilled' && (u.status === 'rejected' || b.status === 'rejected')) {
    partialError.value = u.status === 'rejected' ? u.reason : (b as PromiseRejectedResult).reason
  }
  refreshKey.value++
  loading.value = false
}

async function onChanged() {
  await Promise.all([loadAll(), loadCustomers()])
}

async function refreshEntitlements() {
  refreshing.value = true
  try {
    subscription.value = await api.refreshSubscription()
    await onChanged()
  } catch (e) {
    partialError.value = e
  } finally {
    refreshing.value = false
  }
}

watch(() => session.customerId, loadAll)

onMounted(async () => {
  await loadCustomers()
  await Promise.all([loadAll(), refreshClock()])
})
</script>

<template>
  <main class="page">
    <section class="card customer-bar">
      <div class="who">
        <label class="field">
          고객 전환 <span class="faint">(데모용 로그인: X-Customer-Id 헤더)</span>
          <select v-model="session.customerId" data-testid="customer-select">
            <option v-for="c in customers" :key="c.customerId" :value="c.customerId">
              {{ c.companyName }} — {{ c.pricingModel ? pricingModelLabel[c.pricingModel] : '구독 없음' }} · {{ statusLabel[c.status] }}
            </option>
          </select>
        </label>
        <AppBadge v-if="current" :tone="subscriptionTone[current.status]" :label="statusLabel[current.status]" />
      </div>
      <ol class="flow" aria-label="처리 흐름">
        <li>① 구독 확인</li>
        <li>② 권한 확인</li>
        <li>③ 사용량 발생</li>
        <li>④ Metering</li>
        <li>⑤ UI 반영</li>
      </ol>
      <button type="button" :disabled="loading" @click="onChanged">{{ loading ? '불러오는 중…' : '새로고침' }}</button>
    </section>

    <ErrorNotice v-if="loadError" :error="loadError" :retry="onChanged" />
    <ErrorNotice v-if="partialError" :error="partialError" />

    <template v-if="subscription">
      <div class="grid-2">
        <SubscriptionCard :subscription="subscription" :refreshing="refreshing" @refresh="refreshEntitlements" />
        <FeaturePanel :subscription="subscription" @changed="onChanged" />
      </div>
      <div class="grid-2">
        <UsageCard v-if="usage" :usage="usage" />
        <DemoControls :subscription="subscription" @changed="onChanged" />
      </div>
      <BillingCard v-if="billing" :billing="billing" />
      <CallLogPanel :license-arn="subscription.licenseArn" :refresh-key="refreshKey" />
    </template>
    <p v-else-if="loading" class="muted">불러오는 중…</p>
  </main>
</template>

<style scoped>
.customer-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.who {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.who select {
  max-width: min(520px, 90vw);
}
.flow {
  display: flex;
  gap: 4px;
  list-style: none;
  margin: 0;
  padding: 0;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--text-2);
}
.flow li {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 10px;
}
</style>
