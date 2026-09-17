import { defineConfig } from '@playwright/test'

// 기본은 PC에 설치된 Chrome을 사용한다 (브라우저 추가 다운로드 불필요).
// Playwright 번들 Chromium을 쓰려면: npx playwright install chromium 후 PW_CHANNEL=bundled
const channel = process.env.PW_CHANNEL === 'bundled' ? undefined : (process.env.PW_CHANNEL ?? 'chrome')
// 데모 영상 녹화(npm run demo:video)일 때만 PW_VIDEO=1로 녹화한다
const video = process.env.PW_VIDEO ? { mode: 'on' as const, size: { width: 1280, height: 800 } } : 'off'

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
    // 멈춘 동작이 테스트 전체 제한 시간까지 기다리지 않고 바로 드러나도록 동작별 제한 시간을 둔다
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
    video,
  },
  // 서버가 떠 있지 않으면 백엔드(8080)와 프론트엔드(5173)를 각각 띄운다.
  // 백엔드는 gradlew bootRun 대신 빌드한 jar를 직접 실행한다 (scripts/start-backend.mjs 주석 참고).
  webServer: [
    {
      command: 'node scripts/start-backend.mjs',
      url: 'http://localhost:8080/api/health',
      reuseExistingServer: true,
      timeout: 240_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npm --prefix frontend run dev',
      url: 'http://localhost:5173/',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
})
