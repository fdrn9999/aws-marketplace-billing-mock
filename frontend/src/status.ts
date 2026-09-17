import type { MeteringStatus, SubscriptionStatus } from './api/types'

export type Tone = 'good' | 'warning' | 'critical' | 'neutral' | 'info'

export const subscriptionTone: Record<SubscriptionStatus, Tone> = {
  ACTIVE: 'good',
  EXPIRED: 'critical',
  NOT_SUBSCRIBED: 'warning',
}

export const meteringTone: Record<MeteringStatus, Tone> = {
  PENDING: 'neutral',
  SENDING: 'info',
  SUCCESS: 'good',
  DUPLICATE: 'warning',
  FAILED: 'critical',
}
