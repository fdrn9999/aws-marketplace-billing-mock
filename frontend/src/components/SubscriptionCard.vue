<script setup lang="ts">
import { RouterLink } from 'vue-router'

import type { SubscriptionView } from '../api/types'
import AppBadge from './AppBadge.vue'
import { formatDateTime, formatNumber, pricingModelApis, pricingModelLabel, reasonLabel, statusLabel } from '../format'
import { subscriptionTone } from '../status'

const props = defineProps<{ subscription: SubscriptionView; refreshing: boolean }>()
const emit = defineEmits<{ refresh: [] }>()

function dimensionName(key: string): string {
  return props.subscription.product?.dimensions.find((d) => d.key === key)?.name ?? key
}
</script>

<template>
  <section class="card" data-testid="subscription-card">
    <div class="card-head">
      <h2><span class="step">① 구독 확인</span>구독 상태</h2>
      <button
        v-if="subscription.product?.pricingModel !== 'SUBSCRIPTION' && subscription.registered"
        type="button"
        :disabled="refreshing"
        @click="emit('refresh')"
      >
        {{ refreshing ? '조회 중…' : 'GetEntitlements 다시 조회' }}
      </button>
    </div>

    <div class="status-row">
      <AppBadge
        :tone="subscriptionTone[subscription.status]"
        :label="statusLabel[subscription.status]"
        large
        data-testid="status-badge"
      />
      <span class="reason" data-testid="status-reason">{{ reasonLabel[subscription.reason] }}</span>
    </div>

    <div v-if="subscription.expiringSoon && subscription.status === 'ACTIVE'" class="notice warn">
      <span class="icon" aria-hidden="true">!</span>
      <span>계약 만료까지 {{ subscription.daysUntilExpiry }}일 남았습니다 ({{ formatDateTime(subscription.expiresAt) }}).</span>
    </div>
    <div v-if="subscription.stale" class="notice warn">
      <span class="icon" aria-hidden="true">!</span>
      <span>
        GetEntitlements 조회에 실패했습니다 (<code>{{ subscription.syncError }}</code>).
        {{ subscription.reason === 'ENTITLEMENT_UNVERIFIED'
          ? '마지막 확인이 24시간을 넘어 이용을 막았습니다.'
          : '24시간 이내에 확인한 계약 정보로 판단하고 있습니다.' }}
      </span>
    </div>
    <div v-if="!subscription.registered" class="notice info">
      <span class="icon" aria-hidden="true">i</span>
      <span>
        아직 구독하지 않은 사용자입니다.
        <RouterLink to="/aws-marketplace">AWS Marketplace 시뮬레이터</RouterLink>에서 구독하고 계정을 등록해 보세요.
      </span>
    </div>

    <dl v-if="subscription.registered" class="kv details">
      <dt>회사</dt>
      <dd>{{ subscription.companyName }}</dd>
      <dt>상품</dt>
      <dd>{{ subscription.product?.name }} <span class="faint">({{ subscription.product?.productCode }})</span></dd>
      <dt>과금 모델</dt>
      <dd>
        {{ subscription.product ? pricingModelLabel[subscription.product.pricingModel] : '-' }}
        <div class="faint">{{ subscription.product ? pricingModelApis[subscription.product.pricingModel] : '' }}</div>
      </dd>
      <dt>AWS 계정</dt>
      <dd class="mono">{{ subscription.customerAWSAccountId }}</dd>
      <dt>LicenseArn</dt>
      <dd class="mono">{{ subscription.licenseArn }}</dd>
      <template v-if="subscription.freeTrial">
        <dt>무료 체험</dt>
        <dd>체험 기간 포함</dd>
      </template>
      <template v-if="subscription.lastEntitlementSyncAt">
        <dt>계약 조회 시각</dt>
        <dd>{{ formatDateTime(subscription.lastEntitlementSyncAt) }}</dd>
      </template>
    </dl>

    <div v-if="subscription.entitlements.length" class="table-wrap entitlements">
      <table>
        <caption class="faint">GetEntitlements 결과</caption>
        <thead>
          <tr>
            <th>차원 (Dimension)</th>
            <th class="num">계약 수량 (Value)</th>
            <th>만료 (ExpirationDate)</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in subscription.entitlements" :key="e.dimension + e.expirationDate">
            <td>{{ dimensionName(e.dimension) }} <span class="faint mono">{{ e.dimension }}</span></td>
            <td class="num">{{ formatNumber(e.quantity) }}</td>
            <td>{{ formatDateTime(e.expirationDate) }}</td>
            <td><AppBadge :tone="e.active ? 'good' : 'critical'" :label="e.active ? '유효' : '만료'" /></td>
          </tr>
        </tbody>
      </table>
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
.status-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.reason {
  color: var(--text-2);
}
caption {
  text-align: left;
  padding-bottom: 4px;
}
</style>
