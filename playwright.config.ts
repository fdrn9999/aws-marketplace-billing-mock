import { defineConfig } from '@playwright/test'

// 기본은 PC에 설치된 Chrome을 사용한다 (브라우저 추가 다운로드 불필요).
// Playwright 번들 Chromium을 쓰려면: npx playwright install chromium 후 PW_CHANNEL=bundled
const channel = process.env.PW_CHANNEL === 'bundled' ? undefined : (process.env.PW_CHANNEL ?? 'chrome')
// PW_VIDEO=1이면 실행 영상을 test-results/에 녹화한다
const video = process.env.PW_VIDEO ? { mode: 'on' as const, size: { width: 1280, height: 860 } } : 'off'

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    channel,
    viewport: { width: 1280, height: 860 },
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    video,
    // 녹화할 때는 사람이 따라볼 수 있게 동작 사이에 간격을 둔다
    launchOptions: { slowMo: process.env.PW_VIDEO ? 250 : 0 },
  },
  // 서버가 떠 있지 않으면 루트의 npm run dev로 백엔드(8080)와 프론트엔드(5173)를 함께 띄운다
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173/api/health',
    reuseExistingServer: true,
    timeout: 180_000,
  },
})
