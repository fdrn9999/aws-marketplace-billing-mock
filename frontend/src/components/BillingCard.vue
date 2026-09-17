<script setup lang="ts">
import type { BillingSummary, MeteringStatus } from '../api/types'
import { formatDateTime, formatHour, formatNumber, formatUsd, meteringStatusLabel } from '../format'
import { meteringTone } from '../status'
import AppBadge from './AppBadge.vue'

defineProps<{ billing: BillingSummary }>()

const statuses: MeteringStatus[] = ['PENDING', 'SENDING', 'SUCCESS', 'DUPLICATE', 'FAILED']
</script>

<template>
  <section class="card" data-testid="billing-card">
    <div class="card-head">
      <h2><span class="step">④ Metering / Billing</span>미터링과 예상 청구액</h2>
      <span class="faint">{{ billing.month }} (UTC 기준 월)</span>
    </div>

    <p v-if="!billing.registered" class="muted">등록된 구독이 없어 청구 정보가 없습니다.</p>

    <template v-else>
      <div class="summary">
        <div v-if="billing.contractPriceUsd !== null">
          <span class="faint">계약 금액 (AWS가 별도 청구)</span>
          <span class="value">{{ formatUsd(billing.contractPriceUsd) }}</span>
        </div>
        <div v-if="billing.charges.length">
          <span class="faint">이번 달 사용량 요금 (추정)</span>
          <span class="value" data-testid="metered-amount">{{ formatUsd(billing.meteredAmountUsd) }}</span>
        </div>
        <div v-else>
          <span class="faint">사용량 요금</span>
          <span class="value">해당 없음</span>
          <span class="faint">계약형은 BatchMeterUsage를 쓰지 않습니다</span>
        </div>
      </div>

      <div v-if="billing.charges.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>차원</th>
              <th class="num">AWS 반영 수량</th>
              <th class="num">전송 대기</th>
              <th class="num">단가</th>
              <th class="num">금액</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in billing.charges" :key="c.dimension">
              <td>{{ c.name }}</td>
              <td class="num">{{ formatNumber(c.reportedQuantity) }}</td>
              <td class="num">{{ formatNumber(c.pendingQuantity) }}</td>
              <td class="num">{{ formatUsd(c.unitPriceUsd) }}</td>
              <td class="num">{{ formatUsd(c.amountUsd) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="faint">{{ billing.note }}</p>

      <div v-if="billing.charges.length" class="records">
        <div class="card-head">
          <h3>미터링 레코드 (시간 단위 버킷)</h3>
          <div class="counts">
            <AppBadge
              v-for="s in statuses"
              v-show="(billing.recordCounts[s] ?? 0) > 0"
              :key="s"
              :tone="meteringTone[s]"
              :label="`${meteringStatusLabel[s]} ${billing.recordCounts[s] ?? 0}`"
            />
          </div>
        </div>
        <div class="table-wrap">
          <table data-testid="metering-table">
            <thead>
              <tr>
                <th>시간대 (KST)</th>
                <th>차원</th>
                <th class="num">수량</th>
                <th>상태</th>
                <th class="num">시도</th>
                <th>MeteringRecordId / 사유</th>
                <th>전송 시각</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!billing.records.length">
                <td colspan="7" class="muted">아직 미터링 레코드가 없습니다.</td>
              </tr>
              <tr v-for="r in billing.records" :key="r.id">
                <td class="nowrap">{{ formatDateTime(r.hourStart) }} <span class="faint">~{{ formatHour(new Date(new Date(r.hourStart).getTime() + 3600_000).toISOString()) }}</span></td>
                <td class="mono">{{ r.dimension }}</td>
                <td class="num">{{ formatNumber(r.quantity) }}</td>
                <td><AppBadge :tone="meteringTone[r.status]" :label="meteringStatusLabel[r.status]" /></td>
                <td class="num">{{ r.attempts }}</td>
                <td>
                  <div class="detail">
                    <span v-if="r.meteringRecordId" class="mono">{{ r.meteringRecordId }}</span>
                    <span v-if="r.lastError" class="faint">{{ r.lastError }}</span>
                  </div>
                </td>
                <td class="nowrap">{{ formatDateTime(r.sentAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
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
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
}
.summary > div {
  display: grid;
}
.value {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.records {
  display: grid;
  gap: 8px;
}
.counts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.nowrap {
  white-space: nowrap;
}
.detail {
  display: grid;
  gap: 2px;
  overflow-wrap: anywhere;
}
</style>
