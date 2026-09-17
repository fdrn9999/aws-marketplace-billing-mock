import type { MeteringStatus, PricingModel, StatusReason, SubscriptionStatus } from './api/types'
import { ApiError } from './api/client'

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})
const hourOnly = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', hour12: false })
const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const number = new Intl.NumberFormat('ko-KR')

/** 한국 시간(KST)으로 표시 */
export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '-'
}

export function formatHour(iso: string): string {
  return hourOnly.format(new Date(iso))
}

export function formatUsd(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : usd.format(value)
}

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : number.format(value)
}

export const pricingModelLabel: Record<PricingModel, string> = {
  SUBSCRIPTION: '사용량 기반 (SUBSCRIPTION)',
  CONTRACT: '계약 기반 (CONTRACT)',
  CONTRACT_WITH_SUBSCRIPTION: '혼합형 (CONTRACT + SUBSCRIPTION)',
}

export const pricingModelApis: Record<PricingModel, string> = {
  SUBSCRIPTION: 'BatchMeterUsage (1시간마다, 사용량이 없어도 0 전송)',
  CONTRACT: 'GetEntitlements (만료일 확인)',
  CONTRACT_WITH_SUBSCRIPTION: 'GetEntitlements (기본요금) + BatchMeterUsage (초과요금)',
}

export const statusLabel: Record<SubscriptionStatus, string> = {
  ACTIVE: 'Active',
  EXPIRED: 'Expired',
  NOT_SUBSCRIBED: 'Not Subscribed',
}

export const reasonLabel: Record<StatusReason, string> = {
  ENTITLED: '정상 이용 중',
  NOT_REGISTERED: '등록된 구독 정보가 없습니다',
  SUBSCRIPTION_PENDING: '등록은 완료됐지만 AWS의 구독 완료 이벤트를 기다리는 중입니다',
  ENTITLEMENT_UNVERIFIED: '계약 정보를 확인할 수 없어 이용을 막았습니다 (fail-closed)',
  UNSUBSCRIBED: '구독이 해지되었습니다',
  METERING_REJECTED: 'AWS가 사용량 보고를 거부했습니다 (CustomerNotSubscribed)',
  CONTRACT_EXPIRED: '계약 기간이 만료되었습니다',
}

export const meteringStatusLabel: Record<MeteringStatus, string> = {
  PENDING: '대기',
  SENDING: '전송 중',
  SUCCESS: '성공',
  DUPLICATE: '중복',
  FAILED: '실패',
}

/** 오류 코드별 사용자 안내 (서버 message가 있으면 함께 보여준다) */
const errorHints: Record<string, string> = {
  UNAUTHENTICATED: '고객을 선택해 주세요.',
  NOT_SUBSCRIBED: 'AWS Marketplace에서 구독을 완료하면 이용할 수 있습니다.',
  SUBSCRIPTION_EXPIRED: '구독을 갱신하거나 다시 구독해 주세요.',
  QUOTA_EXCEEDED: '계약을 갱신하거나 상위 요금제를 이용해 주세요.',
  INVALID_REGISTRATION_TOKEN: 'AWS Marketplace에서 "계정 설정"을 다시 눌러 주세요.',
  EXPIRED_REGISTRATION_TOKEN: '등록 토큰은 한 번만 쓸 수 있고 유효 시간이 짧습니다. AWS Marketplace에서 "계정 설정"을 다시 눌러 주세요.',
  ONBOARDING_SESSION_EXPIRED: '등록 시간이 지났습니다. AWS Marketplace에서 "계정 설정"을 다시 눌러 주세요.',
  MARKETPLACE_UNAVAILABLE: 'AWS 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
  ENTITLEMENT_UNAVAILABLE: 'AWS에서 계약 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  METERING_ALREADY_RUNNING: '이전 미터링 작업이 끝난 뒤 다시 실행해 주세요.',
  IDEMPOTENCY_KEY_CONFLICT: '같은 요청 키로 다른 내용을 보냈습니다.',
  NETWORK_ERROR: '백엔드 서버(8080)가 실행 중인지 확인해 주세요.',
}

export function describeError(error: unknown): { title: string; hint?: string; code?: string; requestId?: string } {
  if (error instanceof ApiError) {
    return { title: error.message, hint: errorHints[error.code], code: error.code, requestId: error.requestId }
  }
  return { title: error instanceof Error ? error.message : String(error) }
}
