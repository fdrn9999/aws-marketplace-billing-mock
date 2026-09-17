<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView } from 'vue-router'

import { demo, refreshClock } from './demo'
import { formatDateTime } from './format'

onMounted(refreshClock)

function offsetLabel(seconds: number): string {
  if (seconds === 0) return '실제 시각'
  const hours = Math.round(seconds / 3600)
  return hours >= 24 ? `+${Math.floor(hours / 24)}일 ${hours % 24}시간` : `+${hours}시간`
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <RouterLink to="/" class="brand">
        <span class="logo" aria-hidden="true">◆</span>
        <span>Marketplace Billing <span class="brand-sub">Mock</span></span>
      </RouterLink>
      <nav class="nav">
        <RouterLink to="/">대시보드</RouterLink>
        <RouterLink to="/aws-marketplace">AWS Marketplace 시뮬레이터</RouterLink>
      </nav>
      <div class="clock" data-testid="sim-clock" :title="demo.clock ? `UTC ${demo.clock.now}` : ''">
        <span class="faint">시뮬레이션 시각 (KST)</span>
        <strong v-if="demo.clock">{{ formatDateTime(demo.clock.now) }}</strong>
        <strong v-else-if="demo.clockError" class="offline">서버 연결 안 됨</strong>
        <strong v-else>…</strong>
        <span v-if="demo.clock" class="offset">{{ offsetLabel(demo.clock.offsetSeconds) }}</span>
      </div>
    </div>
  </header>
  <RouterView />
</template>

<style scoped>
.topbar {
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 10;
}
.topbar-inner {
  max-width: 1240px;
  margin: 0 auto;
  padding: 10px 16px;
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  color: var(--text);
  text-decoration: none;
}
.logo {
  color: var(--accent);
}
.brand-sub {
  color: var(--text-3);
  font-weight: 500;
}
.nav {
  display: flex;
  gap: 4px;
  flex: 1;
  flex-wrap: wrap;
}
.nav a {
  color: var(--text-2);
  text-decoration: none;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 14px;
}
.nav a.router-link-exact-active {
  color: var(--accent);
  background: var(--accent-soft);
  font-weight: 600;
}
.clock {
  display: grid;
  justify-items: end;
  line-height: 1.25;
  font-size: 14px;
}
.offset {
  font-size: 12px;
  color: var(--accent);
}
.offline {
  color: var(--critical);
}
@media (max-width: 640px) {
  .topbar-inner {
    gap: 8px 16px;
  }
  /* 좁은 화면: 로고와 시계를 한 줄에, 메뉴는 아래 줄 전체 폭으로 */
  .clock {
    margin-left: auto;
  }
  .nav {
    order: 3;
    flex-basis: 100%;
  }
}
</style>
