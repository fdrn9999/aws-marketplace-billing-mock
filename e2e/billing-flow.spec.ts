import { expect, test as base, type Page } from '@playwright/test'

// 과제 흐름을 실제 브라우저에서 따라가며 검증하고, README용 스크린샷을 docs/screenshots에 저장한다.
const SHOTS = 'docs/screenshots'

/** 매 테스트를 시드 상태에서 시작하고, 끝날 때 브라우저 오류가 없었는지 확인한다 */
const test = base.extend<{ consoleErrors: string[] }>({
  consoleErrors: [
    async ({ page, request }, use) => {
      const res = await request.post('/api/admin/reset')
      expect(res.ok()).toBeTruthy()
      const errors: string[] = []
      page.on('pageerror', (e) => errors.push(e.message))
      page.on('console', (m) => {
        // 의도한 403/400 응답이 남기는 리소스 로드 로그는 제외
        if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) {
          errors.push(m.text())
        }
      })
      await use(errors)
      expect(errors).toEqual([])
    },
    { auto: true },
  ],
})

async function shot(page: Page, name: string) {
  // 스크롤된 상태로 전체 페이지를 찍으면 고정 헤더가 중간에 찍히므로 맨 위로 올린 뒤 촬영한다
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

test('구매 → Fulfillment → 등록 → 사용 → 시간 경과 → 미터링 → 청구 반영', async ({ page }) => {
  // AWS Marketplace 시뮬레이터에서 사용량 기반 상품 구독
  await page.goto('/aws-marketplace')
  await expect(page.getByTestId('product-prod-usage-001')).toBeVisible()
  await shot(page, '01-marketplace')

  await page.getByTestId('subscribe-prod-usage-001').click()
  await expect(page.getByTestId('purchase-result')).toBeVisible()
  await shot(page, '02-purchase')

  // "계정 설정": 브라우저가 등록 토큰을 Fulfillment URL로 form POST → 백엔드가 302로 등록 화면에 보냄
  await page.getByTestId('setup-account').click()
  await expect(page).toHaveURL(/\/register\?onboarding=onb-/)
  await expect(page.getByTestId('register-form')).toContainText('ResolveCustomer')

  // 검증 오류 먼저 확인
  await page.getByTestId('register-submit').click()
  await expect(page.getByTestId('error-notice')).toContainText('VALIDATION_ERROR')
  await expect(page.locator('.field-error').first()).toBeVisible()
  // 첫 번째 오류 칸으로 포커스가 이동하고, 오류 문구가 입력과 연결된다
  await expect(page.locator('input[name=companyName]')).toBeFocused()
  await expect(page.locator('input[name=companyName]')).toHaveAttribute('aria-invalid', 'true')
  await expect(page.locator('input[name=companyName]')).toHaveAttribute('aria-describedby', 'err-companyName')

  await page.fill('input[name=companyName]', '데이터이즈 테스트')
  await page.fill('input[name=contactPerson]', '홍길동')
  await page.fill('input[name=contactPhone]', '010-1234-5678')
  await page.fill('input[name=contactEmail]', 'owner@dataize.example')
  await shot(page, '03-register')
  await page.getByTestId('register-submit').click()
  await expect(page.getByTestId('register-done')).toContainText('Active')

  // 대시보드: 새 고객으로 전환되어 Active
  await page.getByTestId('go-dashboard').click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByTestId('status-badge')).toContainText('Active')
  await expect(page.getByTestId('customer-select')).toContainText('데이터이즈 테스트')

  // 보호 기능 2회 실행 → 사용량 기록
  await page.getByTestId('data-gb').fill('3')
  await page.getByTestId('run-analysis').click()
  await expect(page.getByTestId('run-result')).toBeVisible()
  await page.getByTestId('run-analysis').click()
  await expect(page.getByTestId('usage-analysis_run')).toContainText('2')
  await expect(page.getByTestId('metering-table')).toContainText('대기')
  await shot(page, '04-dashboard-usage')

  // 시간 +1h → 미터링 실행 → BatchMeterUsage 성공
  await page.getByTestId('advance-1h').click()
  await expect(page.getByTestId('demo-message')).toContainText('1시간')
  await page.getByTestId('run-metering').click()
  await expect(page.getByTestId('metering-summary')).toBeVisible()
  await expect(page.getByTestId('metering-table')).toContainText('성공')
  await expect(page.getByTestId('metered-amount')).toContainText('$1.60') // 분석 2회×$0.50 + 6GB×$0.10

  // AWS 호출 로그 원문 펼치기
  const firstCall = page.getByTestId('call-log').locator('details').first()
  await expect(firstCall).toContainText('BatchMeterUsage')
  await firstCall.locator('summary').click()
  await expect(firstCall).toContainText('MeteringRecordId')
  await shot(page, '05-metering-billing')
})

test('구독 상태별 접근 제어: 만료 403, 계약 한도 403, 해지', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('customer-select').selectOption('sub-contract-expired')
  await expect(page.getByTestId('status-badge')).toContainText('Expired')
  await expect(page.getByTestId('access-locked')).toBeVisible()
  await page.getByTestId('run-analysis').click()
  await expect(page.getByTestId('error-notice')).toContainText('SUBSCRIPTION_EXPIRED')
  await shot(page, '06-expired-blocked')

  await page.getByTestId('customer-select').selectOption('sub-contract-active')
  await expect(page.getByTestId('status-badge')).toContainText('Active')
  await expect(page.getByTestId('subscription-card')).toContainText('계약 만료까지 6일')
  for (let i = 0; i < 5; i++) {
    await page.getByTestId('run-analysis').click()
    // 결과 표 첫 행의 "남은 계약 수량" 칸: 95회 사용 상태에서 4 → 0
    await expect(page.getByTestId('run-result').locator('tbody tr').first().locator('td').nth(4)).toHaveText(String(4 - i))
  }
  await page.getByTestId('run-analysis').click()
  await expect(page.getByTestId('error-notice')).toContainText('QUOTA_EXCEEDED')
  await expect(page.getByTestId('usage-analysis_run')).toContainText('100 / 100')
  await shot(page, '07-quota-exceeded')

  await page.getByTestId('customer-select').selectOption('guest')
  await expect(page.getByTestId('status-badge')).toContainText('Not Subscribed')
  await shot(page, '08-not-subscribed')

  await page.getByTestId('customer-select').selectOption('sub-usage-active')
  await expect(page.getByTestId('status-badge')).toContainText('Active')
  await page.getByTestId('cancel-subscription').click()
  await expect(page.getByTestId('status-badge')).toContainText('Expired')
  await expect(page.getByTestId('status-reason')).toContainText('해지')
})

test('등록 토큰 오류는 코드와 함께 등록 화면에 안내된다', async ({ page }) => {
  await page.goto('/aws-marketplace')
  await page.getByTestId('scenario-demo-expired-token').click()
  await expect(page).toHaveURL(/\/register\?error=EXPIRED_REGISTRATION_TOKEN/)
  await expect(page.getByTestId('error-notice')).toContainText('등록 토큰이 만료되었거나 이미 사용되었습니다')
  await shot(page, '09-token-error')

  await page.goto('/aws-marketplace')
  await page.getByTestId('scenario-forged-token-0000').click()
  await expect(page).toHaveURL(/\/register\?error=INVALID_REGISTRATION_TOKEN/)
})

test('모바일 폭에서도 가로 스크롤 없이 표시된다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByTestId('subscription-card')).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await page.screenshot({ path: `${SHOTS}/10-mobile.png` })
})

test('고객을 빠르게 바꿔도 늦게 도착한 이전 고객 응답이 화면을 덮지 않는다', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByTestId('subscription-card')).toContainText('Acme Analytics')
  // Delta Logistics 응답만 1.5초 늦게 오도록 만든다
  await page.route('**/api/me/**', async (route) => {
    if (route.request().headers()['x-customer-id'] === 'sub-contract-expired') {
      await new Promise((resolve) => setTimeout(resolve, 1500))
    }
    await route.continue()
  })
  await page.getByTestId('customer-select').selectOption('sub-contract-expired')
  await page.getByTestId('customer-select').selectOption('sub-hybrid-overage')
  await expect(page.getByTestId('subscription-card')).toContainText('Cobalt Retail')
  await page.waitForTimeout(2500)
  await expect(page.getByTestId('subscription-card')).toContainText('Cobalt Retail')
  await expect(page.getByTestId('status-badge')).toContainText('Active')
})

test('상태가 바뀌면 이전 실행의 오류 안내가 남지 않는다', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('customer-select').selectOption('sub-contract-expired')
  await page.getByTestId('run-analysis').click()
  await expect(page.getByTestId('feature-panel').getByTestId('error-notice')).toBeVisible()
  await page.getByRole('button', { name: '계약 갱신 (+365일)' }).click()
  await expect(page.getByTestId('status-badge')).toContainText('Active')
  await expect(page.getByTestId('feature-panel').getByTestId('error-notice')).toHaveCount(0)
})
