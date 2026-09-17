import { request } from './client'
import type {
  BillingSummary,
  CallLogEntry,
  ClockView,
  CustomerItem,
  EventResult,
  FaultApi,
  FaultMode,
  FaultSnapshot,
  MarketplaceEventType,
  MeteringRunSummary,
  OnboardingView,
  Product,
  PurchaseResult,
  RegisterResponse,
  RunResult,
  SubscriptionView,
  UsageSummary,
} from './types'

// ---- 앱 API
export const api = {
  products: () => request<Product[]>('/api/products', { anonymous: true }),
  customers: () => request<CustomerItem[]>('/api/customers', { anonymous: true }),

  subscription: () => request<SubscriptionView>('/api/me/subscription'),
  refreshSubscription: () => request<SubscriptionView>('/api/me/subscription/refresh', { method: 'POST' }),
  usage: () => request<UsageSummary>('/api/me/usage'),
  billing: () => request<BillingSummary>('/api/me/billing'),

  runAnalysis: (dataGb: number, idempotencyKey?: string) =>
    request<RunResult>('/api/features/analysis/run', {
      method: 'POST',
      body: { dataGb },
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
    }),

  onboarding: (id: string) => request<OnboardingView>(`/api/onboarding/${encodeURIComponent(id)}`, { anonymous: true }),
  register: (form: {
    onboardingId: string
    companyName: string
    contactPerson: string
    contactPhone: string
    contactEmail: string
  }) => request<RegisterResponse>('/api/subscribers', { method: 'POST', body: form, anonymous: true }),

  // ---- 데모 전용
  clock: () => request<ClockView>('/api/admin/clock', { anonymous: true }),
  advanceClock: (advance: string) =>
    request<ClockView>('/api/admin/clock', { method: 'POST', body: { advance }, anonymous: true }),
  runMetering: () => request<MeteringRunSummary>('/api/admin/metering/run', { method: 'POST', anonymous: true }),
  reset: () => request<{ reset: boolean; now: string }>('/api/admin/reset', { method: 'POST', anonymous: true }),
}

// ---- Mock AWS 시뮬레이터 (AWS Marketplace 화면/백오피스 역할)
export const simulator = {
  purchase: (productCode: string, options: { customerAWSAccountId?: string; freeTrial?: boolean; deliverSubscriptionEvent?: boolean }) =>
    request<PurchaseResult>('/mock-aws/_sim/purchase', {
      method: 'POST',
      body: { productCode, ...options },
      anonymous: true,
    }),
  issueToken: (licenseArn: string) =>
    request<PurchaseResult>('/mock-aws/_sim/tokens', { method: 'POST', body: { licenseArn }, anonymous: true }),
  publishEvent: (type: MarketplaceEventType, licenseArn: string, renewDays?: number) =>
    request<EventResult>('/mock-aws/_sim/events', {
      method: 'POST',
      body: { type, licenseArn, renewDays },
      anonymous: true,
    }),
  calls: (licenseArn: string | null, limit = 20) => {
    const params = new URLSearchParams({ limit: String(limit) })
    if (licenseArn) params.set('licenseArn', licenseArn)
    return request<CallLogEntry[]>(`/mock-aws/_sim/calls?${params}`, { anonymous: true })
  },
  faults: () => request<FaultSnapshot>('/mock-aws/_sim/faults', { anonymous: true }),
  setFault: (api: FaultApi, mode: FaultMode, remaining: number) =>
    request<FaultSnapshot>('/mock-aws/_sim/faults', {
      method: 'PUT',
      body: { api, mode, remaining },
      anonymous: true,
    }),
}
