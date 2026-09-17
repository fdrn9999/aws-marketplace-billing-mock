<script setup lang="ts">
import { computed, ref } from 'vue'

import { formatHour, formatNumber } from '../format'

// 최근 12시간 시간대별 사용량 막대 차트 (단일 계열 → 범례 없이 제목으로 계열을 밝힌다)
const props = defineProps<{
  points: { hourStart: string; quantities: Record<string, number> }[]
  dimensionKey: string
  title: string
  unit: string
}>()

const WIDTH = 560
const HEIGHT = 170
const PAD = { top: 14, right: 8, bottom: 26, left: 36 }
const plotW = WIDTH - PAD.left - PAD.right
const plotH = HEIGHT - PAD.top - PAD.bottom

const values = computed(() => props.points.map((p) => p.quantities[props.dimensionKey] ?? 0))

/** 눈금(0, 절반, 최댓값)이 모두 정수로 떨어지는 최댓값 */
const maxY = computed(() => {
  const max = Math.max(...values.value, 0)
  if (max <= 4) return 4
  const step = Math.pow(10, Math.floor(Math.log10(max)))
  const nice = Math.ceil(max / step) * step
  return nice % 2 === 0 ? nice : nice + step
})
const ticks = computed(() => [0, maxY.value / 2, maxY.value])

const slot = computed(() => plotW / Math.max(values.value.length, 1))
const barWidth = computed(() => Math.max(4, Math.min(28, slot.value - 2)))

function y(v: number): number {
  return PAD.top + plotH - (v / maxY.value) * plotH
}

/** 윗모서리만 4px 둥근 막대 (기준선에 붙은 아래쪽은 각지게) */
function barPath(i: number): string {
  const v = values.value[i]
  if (v <= 0) return ''
  const x = PAD.left + i * slot.value + (slot.value - barWidth.value) / 2
  const top = y(v)
  const bottom = PAD.top + plotH
  const r = Math.min(4, barWidth.value / 2, bottom - top)
  const w = barWidth.value
  return `M${x},${bottom} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${bottom} Z`
}

const hover = ref<number | null>(null)
const tooltip = computed(() => {
  if (hover.value === null) return null
  const i = hover.value
  const left = ((PAD.left + i * slot.value + slot.value / 2) / WIDTH) * 100
  return {
    left: `${Math.min(Math.max(left, 12), 88)}%`,
    label: `${formatHour(props.points[i].hourStart)} · ${formatNumber(values.value[i])}${props.unit}`,
  }
})
</script>

<template>
  <figure class="chart">
    <figcaption>
      <strong>{{ title }}</strong>
      <span class="faint">최근 12시간 · 시간대별 ({{ unit }})</span>
    </figcaption>
    <div class="plot" @mouseleave="hover = null">
      <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" role="img" :aria-label="`${title} 최근 12시간 사용량 막대 차트`">
        <g class="grid">
          <g v-for="t in ticks" :key="t">
            <line :x1="PAD.left" :x2="WIDTH - PAD.right" :y1="y(t)" :y2="y(t)" />
            <text :x="PAD.left - 6" :y="y(t) + 4" text-anchor="end">{{ formatNumber(t) }}</text>
          </g>
        </g>
        <path v-for="(_, i) in values" :key="`b${i}`" class="bar" :class="{ dim: hover !== null && hover !== i }" :d="barPath(i)" />
        <g class="xlabels">
          <text
            v-for="(p, i) in points"
            v-show="i % 2 === 1 || i === points.length - 1"
            :key="`x${i}`"
            :x="PAD.left + i * slot + slot / 2"
            :y="HEIGHT - 8"
            text-anchor="middle"
          >
            {{ formatHour(p.hourStart) }}
          </text>
        </g>
        <!-- 막대보다 넓은 투명 영역으로 마우스 hover를 받는다 -->
        <rect
          v-for="(_, i) in values"
          :key="`h${i}`"
          class="hit"
          :x="PAD.left + i * slot"
          :y="PAD.top"
          :width="slot"
          :height="plotH"
          @mouseenter="hover = i"
        />
      </svg>
      <div v-if="tooltip" class="tooltip" :style="{ left: tooltip.left }" role="status">{{ tooltip.label }}</div>
    </div>
    <details class="table-view">
      <summary class="faint">표로 보기</summary>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>시간 (KST)</th>
              <th class="num">{{ title }} ({{ unit }})</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in points" :key="p.hourStart">
              <td>{{ formatHour(p.hourStart) }}</td>
              <td class="num">{{ formatNumber(values[i]) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </figure>
</template>

<style scoped>
.chart {
  margin: 0;
  display: grid;
  gap: 6px;
}
figcaption {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}
.plot {
  position: relative;
  background: var(--chart-surface);
  border-radius: 8px;
}
svg {
  display: block;
  width: 100%;
  height: auto;
}
.grid line {
  stroke: var(--grid);
  stroke-width: 1;
}
.grid text,
.xlabels text {
  fill: var(--text-3);
  font-size: 11px;
}
.bar {
  fill: var(--series-1);
  transition: opacity 0.15s;
}
.bar.dim {
  opacity: 0.45;
}
.hit {
  fill: transparent;
}
.tooltip {
  position: absolute;
  top: 2px;
  transform: translateX(-50%);
  background: var(--text);
  color: var(--surface);
  font-size: 12px;
  padding: 3px 8px;
  border-radius: 6px;
  pointer-events: none;
  white-space: nowrap;
}
.table-view table {
  max-width: 320px;
}
</style>
