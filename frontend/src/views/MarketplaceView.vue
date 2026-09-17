<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'

import { api, simulator } from '../api/endpoints'
import type { Product, PurchaseResult } from '../api/types'
import AppBadge from '../components/AppBadge.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { formatDateTime, formatUsd, pricingModelApis, pricingModelLabel } from '../format'

const products = ref<Product[]>([])
const loadError = shallowRef<unknown>(null)
const purchaseError = shallowRef<unknown>(null)
const busy = ref(false)
const purchase = shallowRef<PurchaseResult | null>(null)
const productName = ref('')

const accountId = ref('')
const freeTrial = ref(false)
const deliverEvent = ref(true)

/** 잘못된 토큰으로 Fulfillment URL에 진입하는 시나리오 */
const errorScenarios = [
  { token: 'demo-expired-token', label: '만료된 토큰', desc: '유효 시간이 지난 등록 토큰 → ExpiredTokenException' },
  { token: 'demo-used-token', label: '이미 사용한 토큰', desc: '한 번 제출된 토큰 재사용 → ExpiredTokenException' },
  { token: 'forged-token-0000', label: '존재하지 않는 토큰', desc: '위조/오타 토큰 → InvalidTokenException' },
]

async function load() {
  loadError.value = null
  try {
    products.value = await api.products()
  } catch (e) {
    loadError.value = e
  }
}

async function subscribe(product: Product) {
  busy.value = true
  purchaseError.value = null
  try {
    purchase.value = await simulator.purchase(product.productCode, {
      customerAWSAccountId: accountId.value.trim() || undefined,
      freeTrial: freeTrial.value,
      deliverSubscriptionEvent: deliverEvent.value,
    })
    productName.value = product.name
  } catch (e) {
    purchaseError.value = e
  } finally {
    busy.value = false
  }
}

async function reissueToken() {
  if (!purchase.value) return
  busy.value = true
  purchaseError.value = null
  try {
    purchase.value = await simulator.issueToken(purchase.value.licenseArn)
  } catch (e) {
    purchaseError.value = e
  } finally {
    busy.value = false
  }
}

function mask(token: string): string {
  return token.length > 10 ? `${token.slice(0, 10)}…` : token
}

onMounted(load)
</script>

<template>
  <main class="page">
    <section class="card aws-hero">
      <div>
        <p class="faint">AWS Marketplace 구매 화면을 흉내 낸 시뮬레이터 (Mock AWS)</p>
        <h1>Dataize Insight 구독</h1>
        <p class="muted">
          구독하면 AWS가 라이선스와 등록 토큰을 만들고, "계정 설정" 버튼이 토큰을 판매자의 Fulfillment URL로 POST합니다 (가이드 p12).
        </p>
      </div>
    </section>

    <ErrorNotice v-if="loadError" :error="loadError" :retry="load" />

    <section class="card options">
      <h2>구매 옵션</h2>
      <div class="option-row">
        <label class="field">
          구매자 AWS 계정 ID (선택)
          <input v-model="accountId" inputmode="numeric" maxlength="12" placeholder="비우면 임의 생성 (12자리)" />
        </label>
        <label class="check"><input v-model="freeTrial" type="checkbox" /> 무료 체험 포함</label>
        <label class="check">
          <input v-model="deliverEvent" type="checkbox" data-testid="deliver-event" />
          구독 완료 이벤트를 앱에 바로 전달
        </label>
      </div>
      <p class="faint">
        이벤트 전달을 끄면 등록 후에도 "구독 대기(SUBSCRIPTION_PENDING)" 상태가 되고, 대시보드의 "구독 시작" 이벤트로 활성화할 수 있습니다.
      </p>
    </section>

    <div class="grid-3">
      <section v-for="p in products" :key="p.productCode" class="card product" :data-testid="`product-${p.productCode}`">
        <div class="product-head">
          <h2>{{ p.name }}</h2>
          <AppBadge tone="info" :label="pricingModelLabel[p.pricingModel]" />
        </div>
        <p class="muted">{{ p.description }}</p>
        <p class="faint">연동 API: {{ pricingModelApis[p.pricingModel] }}</p>
        <ul class="dims">
          <li v-for="d in p.dimensions" :key="d.key">
            {{ d.name }}
            <span v-if="d.meteringUnitPriceUsd !== null" class="price">{{ formatUsd(d.meteringUnitPriceUsd) }} / {{ d.unit }}</span>
          </li>
        </ul>
        <p v-if="p.contractPriceUsd !== null"><strong>계약 금액 {{ formatUsd(p.contractPriceUsd) }}</strong> <span class="faint">/ 12개월</span></p>
        <button type="button" class="primary" :disabled="busy" :data-testid="`subscribe-${p.productCode}`" @click="subscribe(p)">
          구독하기
        </button>
      </section>
    </div>

    <ErrorNotice v-if="purchaseError" :error="purchaseError" />

    <section v-if="purchase" class="card purchased" data-testid="purchase-result">
      <div class="aws-banner">
        <span class="spinner" aria-hidden="true">◌</span>
        <span>구독이 진행 중입니다. 이제 공급업체 웹사이트에서 계정을 설정할 수 있습니다.</span>
        <!-- AWS와 같은 방식: 브라우저가 등록 토큰을 Fulfillment URL로 form POST 한다 -->
        <form method="post" :action="purchase.fulfillmentUrl">
          <input type="hidden" :name="purchase.tokenFieldName" :value="purchase.registrationToken" />
          <button type="submit" class="primary" data-testid="setup-account">계정 설정 ↗</button>
        </form>
      </div>
      <dl class="kv">
        <dt>상품</dt>
        <dd>{{ productName }} <span class="faint">({{ purchase.productCode }})</span></dd>
        <dt>AWS 계정</dt>
        <dd class="mono">{{ purchase.customerAWSAccountId }}</dd>
        <dt>LicenseArn</dt>
        <dd class="mono">{{ purchase.licenseArn }}</dd>
        <dt>등록 토큰</dt>
        <dd>
          <span class="mono">{{ mask(purchase.registrationToken) }}</span>
          <span class="faint"> · {{ formatDateTime(purchase.tokenExpiresAt) }}까지 · 1회용</span>
        </dd>
        <dt>POST 대상</dt>
        <dd class="mono">{{ purchase.fulfillmentUrl }} ({{ purchase.tokenFieldName }})</dd>
        <dt>구독 완료 이벤트</dt>
        <dd>{{ purchase.subscriptionEventDelivered ? '앱에 전달됨' : '전달하지 않음' }}</dd>
      </dl>
      <div class="button-row">
        <button type="button" :disabled="busy" @click="reissueToken">"계정 설정"을 다시 누른 상황 (새 토큰 발급)</button>
      </div>
    </section>

    <section class="card">
      <h2>오류 시나리오</h2>
      <p class="faint">잘못된 토큰으로 Fulfillment URL에 들어가면 백엔드가 오류 코드를 붙여 등록 화면으로 보냅니다.</p>
      <div class="scenarios">
        <form v-for="s in errorScenarios" :key="s.token" method="post" action="/marketplace/fulfillment" class="scenario">
          <input type="hidden" name="x-amzn-marketplace-token" :value="s.token" />
          <button type="submit" :data-testid="`scenario-${s.token}`">{{ s.label }}</button>
          <span class="faint">{{ s.desc }}</span>
        </form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.aws-hero {
  background: #232f3e;
  color: #fff;
  border-color: #232f3e;
}
.aws-hero .faint,
.aws-hero .muted {
  color: #d5dbe1;
}
.aws-hero h1 {
  margin: 4px 0 6px;
}
.options {
  display: grid;
  gap: 8px;
}
.option-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px 20px;
}
.option-row input:not([type='checkbox']) {
  width: 230px;
}
.check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.product {
  display: grid;
  gap: 8px;
  align-content: start;
}
.product-head {
  display: grid;
  gap: 6px;
  justify-items: start;
}
.product button {
  justify-self: start;
}
.dims {
  margin: 0;
  padding-left: 18px;
  font-size: 14px;
}
.price {
  color: var(--text-2);
  margin-left: 6px;
}
.purchased {
  display: grid;
  gap: 12px;
  border: 2px solid var(--accent);
}
.aws-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  background: var(--accent-soft);
  border-radius: 10px;
  padding: 10px 12px;
}
.aws-banner span:nth-child(2) {
  flex: 1;
  min-width: 200px;
}
.scenarios {
  display: grid;
  gap: 8px;
  margin-top: 8px;
}
.scenario {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
