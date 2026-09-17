<script setup lang="ts">
import { computed } from 'vue'

import type { UsageSummary } from '../api/types'
import { formatDateTime, formatNumber, formatUsd } from '../format'
import HourlyChart from './HourlyChart.vue'

const props = defineProps<{ usage: UsageSummary }>()

const primary = computed(() => props.usage.dimensions[0] ?? null)

function percent(used: number, total: number | null): number {
  if (!total) return used > 0 ? 100 : 0
  return Math.min(100, Math.round((used / total) * 100))
}
</script>

<template>
  <section class="card" data-testid="usage-card">
    <div class="card-head">
      <h2><span class="step">③ 사용량</span>사용량 현황</h2>
      <span v-if="usage.period" class="faint">
        {{ usage.period.label }}: {{ formatDateTime(usage.period.start) }} ~ {{ formatDateTime(usage.period.end) }}
      </span>
    </div>

    <p v-if="!usage.registered" class="muted">등록된 구독이 없어 사용량이 없습니다.</p>

    <div v-else class="dims">
      <div v-for="d in usage.dimensions" :key="d.key" class="dim" :data-testid="`usage-${d.key}`">
        <div class="dim-head">
          <strong>{{ d.name }}</strong>
          <span class="faint mono">{{ d.key }}</span>
        </div>
        <div class="stats">
          <div>
            <span class="faint">기간 사용량</span>
            <span class="value">{{ formatNumber(d.used) }}<small>{{ d.unit }}</small></span>
          </div>
          <div v-if="d.included !== null">
            <span class="faint">계약 수량 사용</span>
            <span class="value">{{ formatNumber(d.includedUsed) }} / {{ formatNumber(d.included) }}<small>{{ d.unit }}</small></span>
          </div>
          <div v-if="usage.pricingModel !== 'CONTRACT'">
            <span class="faint">미터링 대상</span>
            <span class="value">{{ formatNumber(d.metered) }}<small>{{ d.unit }}</small></span>
          </div>
          <div v-if="d.unitPriceUsd !== null">
            <span class="faint">단가</span>
            <span class="value">{{ formatUsd(d.unitPriceUsd) }}<small>/{{ d.unit }}</small></span>
          </div>
        </div>
        <div v-if="d.included !== null" class="quota">
          <div
            class="meter"
            :class="{ full: d.includedRemaining === 0 }"
            role="meter"
            :aria-valuenow="d.includedUsed"
            aria-valuemin="0"
            :aria-valuemax="d.included"
            :aria-label="`${d.name} 계약 수량 사용률`"
          >
            <span :style="{ width: `${percent(d.includedUsed, d.included)}%` }" />
          </div>
          <span class="faint">
            {{ percent(d.includedUsed, d.included) }}% 사용 · 남은 수량 {{ formatNumber(d.includedRemaining) }}{{ d.unit }}
            <template v-if="usage.pricingModel === 'CONTRACT_WITH_SUBSCRIPTION' && d.includedRemaining === 0">
              · 이후 사용은 초과 요금으로 미터링
            </template>
            <template v-if="usage.pricingModel === 'CONTRACT' && d.includedRemaining === 0"> · 추가 사용 불가</template>
          </span>
        </div>
      </div>

      <HourlyChart
        v-if="primary"
        :points="usage.hourly"
        :dimension-key="primary.key"
        :title="primary.name"
        :unit="primary.unit"
      />
    </div>
  </section>
</template>

<style scoped>
.dims {
  display: grid;
  gap: 14px;
}
.dim {
  display: grid;
  gap: 6px;
}
.dim-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 22px;
}
.stats > div {
  display: grid;
}
.value {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.value small {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-2);
  margin-left: 2px;
}
.quota {
  display: grid;
  gap: 4px;
}
</style>
