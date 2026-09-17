package io.github.fdrn9999.marketplace.domain;

/** 구독이 현재 {@link SubscriptionStatus}가 된 이유. */
public enum StatusReason {
    /** ACTIVE: 정상 이용 가능 */
    ENTITLED,
    /** NOT_SUBSCRIBED: 이 고객의 구독자 레코드가 없음 */
    NOT_REGISTERED,
    /** NOT_SUBSCRIBED: 등록은 했지만 "구독 완료" 이벤트가 아직 도착하지 않음 */
    SUBSCRIPTION_PENDING,
    /** NOT_SUBSCRIBED: 계약형 상품인데 Entitlement를 한 번도 확인하지 못함(fail-closed) */
    ENTITLEMENT_UNVERIFIED,
    /** EXPIRED: 구매자가 구독을 해지함 */
    UNSUBSCRIBED,
    /** EXPIRED: BatchMeterUsage가 CustomerNotSubscribed를 반환함 */
    METERING_REJECTED,
    /** EXPIRED: 모든 Entitlement의 ExpirationDate가 지남 */
    CONTRACT_EXPIRED
}
