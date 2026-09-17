<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { ApiError } from '../api/client'
import { api } from '../api/endpoints'
import type { OnboardingView, RegisterResponse } from '../api/types'
import AppBadge from '../components/AppBadge.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { formatDateTime, pricingModelLabel, reasonLabel, statusLabel } from '../format'
import { session } from '../session'
import { subscriptionTone } from '../status'

const route = useRoute()
const router = useRouter()

const onboardingId = computed(() => (typeof route.query.onboarding === 'string' ? route.query.onboarding : null))
const redirectError = computed(() => (typeof route.query.error === 'string' ? route.query.error : null))

/** Fulfillment URL이 붙여 보낸 오류 코드를 화면 오류로 바꾼다 */
const redirectErrorTitles: Record<string, string> = {
  INVALID_REGISTRATION_TOKEN: '유효하지 않은 등록 토큰입니다',
  EXPIRED_REGISTRATION_TOKEN: '등록 토큰이 만료되었거나 이미 사용되었습니다',
  UNKNOWN_PRODUCT: '이 서비스에서 판매하지 않는 제품입니다',
  MARKETPLACE_UNAVAILABLE: 'AWS Marketplace API를 일시적으로 사용할 수 없습니다',
}
const redirectApiError = computed(() =>
  redirectError.value
    ? new ApiError(400, redirectError.value, redirectErrorTitles[redirectError.value] ?? '계정 등록을 시작할 수 없습니다')
    : null,
)

const onboarding = shallowRef<OnboardingView | null>(null)
const loadError = shallowRef<unknown>(null)
const submitError = shallowRef<unknown>(null)
const submitting = ref(false)
const result = shallowRef<RegisterResponse | null>(null)
const fieldErrors = ref<Record<string, string>>({})

const form = reactive({
  companyName: '',
  contactPerson: '',
  contactPhone: '',
  contactEmail: '',
})

async function load() {
  if (!onboardingId.value) return
  loadError.value = null
  try {
    onboarding.value = await api.onboarding(onboardingId.value)
  } catch (e) {
    loadError.value = e
  }
}

async function submit() {
  if (!onboarding.value) return
  submitting.value = true
  submitError.value = null
  fieldErrors.value = {}
  try {
    result.value = await api.register({ onboardingId: onboarding.value.onboardingId, ...form })
  } catch (e) {
    if (e instanceof ApiError && e.code === 'VALIDATION_ERROR') {
      fieldErrors.value = (e.details.fields as Record<string, string>) ?? {}
    }
    submitError.value = e
  } finally {
    submitting.value = false
  }
}

function goToDashboard() {
  if (!result.value) return
  session.customerId = result.value.subscriberId
  router.push('/')
}

onMounted(load)
</script>

<template>
  <main class="page narrow">
    <section class="card">
      <p class="faint">판매자 SaaS · 계정 등록 (QuickStart RegisterNewMarketplaceCustomer)</p>
      <h1>Dataize Insight 계정 만들기</h1>
    </section>

    <template v-if="redirectApiError">
      <ErrorNotice :error="redirectApiError" />
      <p><RouterLink to="/aws-marketplace">AWS Marketplace 시뮬레이터로 돌아가기</RouterLink></p>
    </template>

    <section v-else-if="!onboardingId" class="card">
      <p class="muted">
        이 화면은 AWS Marketplace에서 "계정 설정"을 눌렀을 때 열립니다.
        <RouterLink to="/aws-marketplace">시뮬레이터에서 구독하기</RouterLink>
      </p>
    </section>

    <template v-else>
      <ErrorNotice v-if="loadError" :error="loadError" :retry="load" />

      <section v-if="result" class="card done" data-testid="register-done">
        <h2>등록이 완료되었습니다</h2>
        <div class="button-row">
          <AppBadge :tone="subscriptionTone[result.subscription.status]" :label="statusLabel[result.subscription.status]" large />
          <span class="muted">{{ reasonLabel[result.subscription.reason] }}</span>
        </div>
        <p v-if="result.alreadyRegistered" class="muted">이미 등록된 계정이어서 연락처 정보만 갱신했습니다.</p>
        <p v-if="result.entitlementSyncError" class="notice warn">
          <span class="icon">!</span>
          <span>계약 정보 조회에 실패했습니다 ({{ result.entitlementSyncError }}). 대시보드에서 다시 조회됩니다.</span>
        </p>
        <dl class="kv">
          <dt>고객 ID</dt>
          <dd class="mono">{{ result.subscriberId }}</dd>
          <dt>회사</dt>
          <dd>{{ result.subscription.companyName }}</dd>
        </dl>
        <div class="button-row">
          <button type="button" class="primary" data-testid="go-dashboard" @click="goToDashboard">이 고객으로 대시보드 보기</button>
        </div>
      </section>

      <section v-else-if="onboarding" class="card" data-testid="register-form">
        <div class="notice ok">
          <span class="icon" aria-hidden="true">●</span>
          <span>
            AWS Marketplace 구매 확인됨 (ResolveCustomer) — 계정
            <span class="mono">{{ onboarding.customerAWSAccountId }}</span>, {{ onboarding.productName }}
            ({{ pricingModelLabel[onboarding.pricingModel] }})
          </span>
        </div>
        <p v-if="onboarding.alreadyRegistered" class="notice warn">
          <span class="icon">!</span><span>이미 등록된 구매입니다. 제출하면 연락처 정보가 갱신됩니다.</span>
        </p>
        <p class="faint">이 등록 화면은 {{ formatDateTime(onboarding.expiresAt) }}까지 유효합니다.</p>

        <form class="form" novalidate @submit.prevent="submit">
          <label class="field">
            회사명
            <input v-model="form.companyName" name="companyName" autocomplete="organization" required />
            <span v-if="fieldErrors.companyName" class="field-error">{{ fieldErrors.companyName }}</span>
          </label>
          <label class="field">
            담당자 이름
            <input v-model="form.contactPerson" name="contactPerson" autocomplete="name" required />
            <span v-if="fieldErrors.contactPerson" class="field-error">{{ fieldErrors.contactPerson }}</span>
          </label>
          <label class="field">
            연락처
            <input v-model="form.contactPhone" name="contactPhone" autocomplete="tel" placeholder="010-1234-5678" required />
            <span v-if="fieldErrors.contactPhone" class="field-error">{{ fieldErrors.contactPhone }}</span>
          </label>
          <label class="field">
            이메일
            <input v-model="form.contactEmail" name="contactEmail" type="email" autocomplete="email" required />
            <span v-if="fieldErrors.contactEmail" class="field-error">{{ fieldErrors.contactEmail }}</span>
          </label>
          <div class="button-row">
            <button type="submit" class="primary" :disabled="submitting" data-testid="register-submit">
              {{ submitting ? '등록 중…' : '계정 등록' }}
            </button>
          </div>
        </form>
        <ErrorNotice v-if="submitError" :error="submitError" />
      </section>
    </template>
  </main>
</template>

<style scoped>
.narrow {
  max-width: 720px;
}
.card {
  display: grid;
  gap: 10px;
}
.form {
  display: grid;
  gap: 12px;
}
.done {
  border: 2px solid var(--good);
}
</style>
