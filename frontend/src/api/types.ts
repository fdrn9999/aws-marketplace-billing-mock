// 백엔드 API 응답 타입 (backend의 Java record DTO와 같은 계약)

export type PricingModel = 'SUBSCRIPTION' | 'CONTRACT' | 'CONTRACT_WITH_SUBSCRIPTION'
export type SubscriptionStatus = 'ACTIVE' | 'EXPIRED' | 'NOT_SUBSCRIBED'
export type StatusReason =
  | 'ENTITLED'
  | 'NOT_REGISTERED'
  | 'SUBSCRIPTION_PENDING'
  | 'ENTITLEMENT_UNVERIFIED'
  | 'UNSUBSCRIBED'
  | 'METERING_REJECTED'
  | 'CONTRACT_EXPIRED'
export type MeteringStatus = 'PENDING' | 'SENDING' | 'SUCCESS' | 'DUPLICATE' | 'FAILED'

export interface Dimension {
  key: string
  name: string
  unit: string
  meteringUnitPriceUsd: number | null
}

export interface Product {
  productCode: string
  name: string
  description: string
  pricingModel: PricingModel
  contractPriceUsd: number | null
  dimensions: Dimension[]
}

export interface CustomerItem {
  customerId: string
  companyName: string
  productName: string | null
  pricingModel: PricingModel | null
  status: SubscriptionStatus
  reason: StatusReason
}

export interface EntitlementView {
  dimension: string
  quantity: number | null
  expirationDate: string | null
  active: boolean
}

export interface SubscriptionView {
  customerId: string
  registered: boolean
  companyName: string | null
  customerAWSAccountId: string | null
  licenseArn: string | null
  product: Product | null
  status: SubscriptionStatus
  reason: StatusReason
  canUseFeatures: boolean
  freeTrial: boolean
  entitlements: EntitlementView[]
  expiresAt: string | null
  daysUntilExpiry: number | null
  expiringSoon: boolean
  lastEntitlementSyncAt: string | null
  stale: boolean
  syncError: string | null
  serverTime: string
}

export interface UsageLine {
  dimension: string
  quantity: number
  includedQuantity: number
  meteredQuantity: number
  includedRemaining: number | null
}

export interface RunResult {
  runId: string
  subscriberId: string
  usage: UsageLine[]
  overage: boolean
  replayed: boolean
  recordedAt: string
}

export interface DimensionUsage {
  key: string
  name: string
  unit: string
  used: number
  included: number | null
  includedUsed: number
  includedRemaining: number | null
  metered: number
  unitPriceUsd: number | null
}

export interface UsageSummary {
  customerId: string
  registered: boolean
  pricingModel: PricingModel | null
  period: { start: string; end: string | null; label: string } | null
  dimensions: DimensionUsage[]
  hourly: { hourStart: string; quantities: Record<string, number> }[]
  serverTime: string
}

export interface MeteringRecordView {
  id: string
  dimension: string
  hourStart: string
  quantity: number
  status: MeteringStatus
  meteringRecordId: string | null
  attempts: number
  lastError: string | null
  sentAt: string | null
}

export interface ChargeLine {
  dimension: string
  name: string
  reportedQuantity: number
  pendingQuantity: number
  unitPriceUsd: number | null
  amountUsd: number
}

export interface BillingSummary {
  customerId: string
  registered: boolean
  month: string
  contractPriceUsd: number | null
  charges: ChargeLine[]
  meteredAmountUsd: number
  recordCounts: Partial<Record<MeteringStatus, number>>
  records: MeteringRecordView[]
  note: string
  serverTime: string
}

export interface MeteringRunSummary {
  startedAt: string
  zeroRecordsCreated: number
  claimed: number
  batches: number
  success: number
  duplicate: number
  customerNotSubscribed: number
  unprocessed: number
  retryLater: number
  failed: number
  errors: string[]
}

export interface ClockView {
  now: string
  currentHourStart: string
  offsetSeconds: number
}

export interface PurchaseResult {
  registrationToken: string
  fulfillmentUrl: string
  tokenFieldName: string
  licenseArn: string
  customerAWSAccountId: string
  productCode: string
  tokenExpiresAt: string
  subscriptionEventDelivered: boolean
}

export interface OnboardingView {
  onboardingId: string
  customerAWSAccountId: string
  licenseArn: string
  productCode: string
  productName: string
  pricingModel: PricingModel
  expiresAt: string
  alreadyRegistered: boolean
}

export interface RegisterResponse {
  subscriberId: string
  alreadyRegistered: boolean
  entitlementSyncError: string | null
  subscription: SubscriptionView
}

export type MarketplaceEventType = 'SUBSCRIPTION_STARTED' | 'ENTITLEMENT_UPDATED' | 'SUBSCRIPTION_CANCELLED'

export interface EventResult {
  event: { type: MarketplaceEventType; licenseArn: string }
  delivered: boolean
}

export interface CallLogEntry {
  id: string
  at: string
  api: string
  licenseArn: string | null
  httpStatus: number
  errorType: string | null
  request: unknown
  response: unknown
  durationMs: number
}

export type FaultApi = 'RESOLVE_CUSTOMER' | 'GET_ENTITLEMENTS' | 'BATCH_METER_USAGE'
export type FaultMode = 'NONE' | 'THROTTLE' | 'INTERNAL_ERROR' | 'UNPROCESSED'
export type FaultSnapshot = Record<FaultApi, { mode: FaultMode; remaining: number }>

/** 앱 API 오류 본문 */
export interface ApiErrorBody {
  error: {
    code: string
    message: string
    details?: Record<string, unknown>
    requestId?: string
  }
}
