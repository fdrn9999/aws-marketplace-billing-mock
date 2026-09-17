import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 제출용 데모 영상 녹화 스크립트 (npm run demo:video).
 * 녹화 영상에는 마우스 커서가 찍히지 않으므로 페이지에 가짜 커서, 클릭 효과, 단계 자막을 그려 넣고
 * 사람이 따라볼 수 있는 속도로 진행한다. 일반 테스트 실행(npm run e2e)에서는 건너뛴다.
 */
test.skip(!process.env.PW_VIDEO, '데모 영상 녹화 전용 (PW_VIDEO=1)')
test.use({ viewport: { width: 1280, height: 800 } })
test.setTimeout(240_000)

/** 모든 페이지에 커서 · 클릭 효과 · 자막 · 강조 표시를 주입한다 */
const OVERLAY_SCRIPT = `
(() => {
  const install = () => {
    if (document.getElementById('__demo_cursor')) return
    const style = document.createElement('style')
    style.textContent = \`
      #__demo_cursor { position: fixed; left: 0; top: 0; width: 22px; height: 22px; margin: -11px 0 0 -11px;
        border-radius: 50%; background: rgba(229, 57, 53, 0.35); border: 3px solid #e53935; z-index: 2147483647;
        pointer-events: none; box-shadow: 0 0 0 2px #fff; }
      .__demo_ripple { position: fixed; width: 22px; height: 22px; margin: -11px 0 0 -11px; border-radius: 50%;
        border: 3px solid #e53935; z-index: 2147483646; pointer-events: none; animation: __demo_ripple 0.6s ease-out forwards; }
      @keyframes __demo_ripple { to { transform: scale(3.2); opacity: 0; } }
      #__demo_caption { position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); max-width: 88%;
        background: rgba(17, 24, 39, 0.92); color: #fff; padding: 12px 22px; border-radius: 12px; z-index: 2147483645;
        font: 600 20px/1.45 'Malgun Gothic', system-ui, sans-serif; text-align: center; pointer-events: none;
        box-shadow: 0 6px 24px rgba(0,0,0,0.3); }
      #__demo_caption small { display: block; font-weight: 400; font-size: 15px; color: #d1d5db; margin-top: 2px; }
      #__demo_caption:empty { display: none; }
      .__demo_focus { outline: 4px solid #e53935 !important; outline-offset: 3px !important; border-radius: 8px; }
    \`
    document.head.appendChild(style)
    const cursor = document.createElement('div')
    cursor.id = '__demo_cursor'
    const last = JSON.parse(sessionStorage.getItem('__demo_pos') || '[640,400]')
    cursor.style.transform = 'translate(' + last[0] + 'px,' + last[1] + 'px)'
    document.body.appendChild(cursor)
    const caption = document.createElement('div')
    caption.id = '__demo_caption'
    caption.innerHTML = sessionStorage.getItem('__demo_caption') || ''
    document.body.appendChild(caption)
    document.addEventListener('mousemove', (e) => {
      cursor.style.transform = 'translate(' + e.clientX + 'px,' + e.clientY + 'px)'
      sessionStorage.setItem('__demo_pos', JSON.stringify([e.clientX, e.clientY]))
    }, true)
    document.addEventListener('mousedown', (e) => {
      const ripple = document.createElement('div')
      ripple.className = '__demo_ripple'
      ripple.style.left = e.clientX + 'px'
      ripple.style.top = e.clientY + 'px'
      document.body.appendChild(ripple)
      setTimeout(() => ripple.remove(), 700)
    }, true)
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install)
  else install()
})()
`

class Director {
  constructor(private readonly page: Page) {}

  /** 하단 자막. 페이지를 새로 불러와도 유지된다 */
  async caption(title: string, detail = '') {
    await this.page.evaluate(
      ([t, d]) => {
        const html = d ? `${t}<small>${d}</small>` : t
        sessionStorage.setItem('__demo_caption', html)
        const el = document.getElementById('__demo_caption')
        if (el) el.innerHTML = html
      },
      [title, detail],
    )
  }

  async wait(ms: number) {
    await this.page.waitForTimeout(ms)
  }

  /** 요소가 화면 가운데쯤 오도록 부드럽게 스크롤 */
  async scrollTo(target: Locator) {
    await target.evaluate((el) => {
      const top = el.getBoundingClientRect().top + window.scrollY - 90
      window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
    })
    await this.wait(900)
  }

  /** 커서를 요소 가운데로 천천히 옮기고 잠시 강조한다 */
  async pointAt(target: Locator) {
    await target.scrollIntoViewIfNeeded()
    const box = await target.boundingBox()
    if (!box) throw new Error('요소 위치를 찾을 수 없습니다')
    await this.page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 30 })
    await target.evaluate((el) => el.classList.add('__demo_focus'))
    await this.wait(550)
  }

  async click(target: Locator, after = 900) {
    await this.pointAt(target)
    await target.evaluate((el) => el.classList.remove('__demo_focus'))
    await target.click()
    await this.wait(after)
  }

  async select(target: Locator, value: string, after = 1200) {
    await this.pointAt(target)
    await target.evaluate((el) => el.classList.remove('__demo_focus'))
    await target.selectOption(value)
    await this.wait(after)
  }

  async type(target: Locator, text: string) {
    await this.click(target, 150)
    await target.pressSequentially(text, { delay: 45 })
    await this.wait(250)
  }

  /** 결과 영역을 잠깐 강조해서 시선을 끈다 */
  async highlight(target: Locator, ms = 2200) {
    await this.scrollTo(target)
    await target.evaluate((el) => el.classList.add('__demo_focus'))
    await this.wait(ms)
    await target.evaluate((el) => el.classList.remove('__demo_focus'))
  }
}

test('데모 영상: 구독 → 권한 → 사용량 → 미터링 → UI 반영', async ({ page, request }) => {
  expect((await request.post('/api/admin/reset')).ok()).toBeTruthy()
  await page.addInitScript(OVERLAY_SCRIPT)
  await page.addInitScript(() => sessionStorage.removeItem('__demo_caption'))
  const d = new Director(page)
  const id = (testId: string) => page.getByTestId(testId)

  // 0. 소개
  await page.goto('/')
  await expect(id('subscription-card')).toBeVisible()
  await d.caption('AWS Marketplace Billing Mock', '구독 확인 → 권한 확인 → 사용량 발생 → Metering → UI 반영')
  await d.wait(3000)

  // 1. 만료 고객 차단
  await d.caption('① 구독 확인: 계약이 만료된 고객으로 전환', '데모용 로그인 = X-Customer-Id 헤더')
  await d.select(id('customer-select'), 'sub-contract-expired')
  await d.highlight(id('status-badge'), 1800)
  await d.caption('② 권한 확인: 보호 기능 실행 시도', '화면과 별개로 서버가 직접 구독 상태를 검사합니다')
  await d.click(id('run-analysis'), 600)
  await expect(id('error-notice')).toContainText('SUBSCRIPTION_EXPIRED')
  await d.caption('403 SUBSCRIPTION_EXPIRED — 기능이 차단됩니다', '사유: CONTRACT_EXPIRED (ExpirationDate가 지남)')
  await d.highlight(id('error-notice'), 2600)

  // 2. 계약 갱신 이벤트
  await d.caption('AWS에서 계약 갱신 이벤트 발생', 'ENTITLEMENT_UPDATED → 앱이 GetEntitlements로 다시 조회')
  await d.click(page.getByRole('button', { name: '계약 갱신 (+365일)' }), 800)
  await expect(id('status-badge')).toContainText('Active')
  await d.caption('다시 Active — 새 계약 만료일이 반영됐습니다')
  await d.highlight(id('subscription-card'), 2600)

  // 3. Marketplace에서 새로 구독
  await d.caption('AWS Marketplace 시뮬레이터로 이동', '사용량 기반(SUBSCRIPTION) 상품을 새로 구독합니다')
  await d.click(page.getByRole('link', { name: 'AWS Marketplace 시뮬레이터' }), 1200)
  await d.click(id('subscribe-prod-usage-001'), 800)
  await expect(id('purchase-result')).toBeVisible()
  await d.caption('구독 완료: AWS가 라이선스와 1회용 등록 토큰을 발급', '"계정 설정"은 토큰을 판매자의 Fulfillment URL로 POST 합니다')
  await d.highlight(id('purchase-result'), 3000)
  await d.click(id('setup-account'), 400)

  // 4. 등록
  await expect(id('register-form')).toBeVisible()
  await d.caption('Fulfillment URL이 ResolveCustomer로 구매자를 확인', 'AWS 계정과 상품이 확인된 뒤 등록 화면으로 이동(302)')
  await d.highlight(page.locator('[data-testid=register-form] .notice.ok'), 2800)
  await d.caption('회사 · 담당자 정보 입력')
  await d.type(page.locator('input[name=companyName]'), '데이터이즈 데모')
  await d.type(page.locator('input[name=contactPerson]'), '홍길동')
  await d.type(page.locator('input[name=contactPhone]'), '010-1234-5678')
  await d.type(page.locator('input[name=contactEmail]'), 'demo@dataize.example')
  await d.click(id('register-submit'), 800)
  await expect(id('register-done')).toContainText('Active')
  await d.caption('등록 완료 → 구독 상태 Active')
  await d.highlight(id('register-done'), 2200)
  await d.click(id('go-dashboard'), 1500)

  // 5. 사용량 발생
  await expect(id('status-badge')).toContainText('Active')
  await d.caption('③ 사용량 발생: AI 분석 실행', '1회 실행 = 분석 1회 + 처리 데이터 3GB')
  await d.pointAt(id('data-gb'))
  await id('data-gb').fill('3')
  await d.click(id('run-analysis'), 1000)
  await d.click(id('run-analysis'), 1000)
  await d.highlight(id('run-result'), 2200)
  await d.caption('사용량이 기록되고, 미터링 레코드는 아직 "대기"', '현재 시간대 버킷은 계속 누적되므로 바로 보내지 않습니다')
  await d.highlight(id('usage-card'), 2500)
  await d.highlight(id('metering-table'), 2500)

  // 6. 미터링
  await d.caption('④ Metering: 시계를 1시간 앞으로', '시간대가 마감되어야 BatchMeterUsage로 보낼 수 있습니다')
  await d.click(id('advance-1h'), 1200)
  await d.caption('미터링 실행 → BatchMeterUsage 호출', '마감된 시간대를 25건 단위로 전송')
  await d.click(id('run-metering'), 800)
  await expect(id('metering-summary')).toBeVisible()
  await d.highlight(id('metering-summary'), 2500)
  await expect(id('metering-table')).toContainText('성공')
  await d.caption('⑤ UI 반영: 레코드 "성공" + MeteringRecordId, 예상 청구액 $1.60', '분석 2회 × $0.50 + 데이터 6GB × $0.10')
  await d.highlight(id('billing-card'), 3500)

  const firstCall = id('call-log').locator('details').first()
  await d.caption('AWS API 호출 로그', '앱이 실제로 보낸 요청과 받은 응답 원문 (LicenseArn 기반)')
  await d.click(firstCall.locator('summary'), 600)
  await d.scrollTo(firstCall)
  await d.wait(3500)

  // 7. 계약 한도
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  await d.wait(800)
  await d.caption('계약 기반 고객: 계약 100회 중 95회 사용', '계약 수량 안에서만 기능을 쓸 수 있습니다')
  await d.select(id('customer-select'), 'sub-contract-active')
  await d.highlight(id('usage-card'), 2000)
  await d.pointAt(id('run-analysis'))
  for (let i = 0; i < 6; i++) {
    await id('run-analysis').click()
    await d.wait(450)
  }
  await expect(id('error-notice')).toContainText('QUOTA_EXCEEDED')
  await d.caption('6번째 실행: 403 QUOTA_EXCEEDED', '남은 계약 수량 0 — 서버가 구독 단위 락 안에서 확인합니다')
  await d.highlight(id('feature-panel'), 3000)
  await d.highlight(id('usage-card'), 2500)

  await d.caption('데모 끝 — 시나리오와 설계는 README를 참고해 주세요')
  await d.wait(2500)
})
