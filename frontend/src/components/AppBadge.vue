<script setup lang="ts">
// 상태 표시 배지: 색만으로 의미를 전달하지 않도록 아이콘과 라벨을 함께 쓴다
import type { Tone } from '../status'

const props = withDefaults(defineProps<{ tone: Tone; label: string; large?: boolean }>(), { large: false })

const icons: Record<Tone, string> = {
  good: '●',
  warning: '!',
  critical: '✕',
  neutral: '○',
  info: 'i',
}
</script>

<template>
  <span class="badge" :class="[props.tone, { large: props.large }]">
    <span class="badge-icon" aria-hidden="true">{{ icons[props.tone] }}</span>
    <span>{{ props.label }}</span>
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  padding: 1px 9px 1px 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text);
  background: var(--neutral-soft);
  white-space: nowrap;
}
.badge.large {
  font-size: 15px;
  padding: 4px 14px 4px 10px;
}
.badge-icon {
  display: inline-grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  font-size: 10px;
  color: #fff;
  background: var(--text-3);
}
.large .badge-icon {
  width: 20px;
  height: 20px;
  font-size: 12px;
}
.good {
  background: var(--good-soft);
}
.good .badge-icon {
  background: var(--good);
}
.warning {
  background: var(--warning-soft);
}
.warning .badge-icon {
  background: var(--warning);
  color: #1a1a19;
}
.critical {
  background: var(--critical-soft);
}
.critical .badge-icon {
  background: var(--critical);
}
.info {
  background: var(--accent-soft);
}
.info .badge-icon {
  background: var(--accent);
}
</style>
