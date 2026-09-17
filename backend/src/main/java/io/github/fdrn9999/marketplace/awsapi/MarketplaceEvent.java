package io.github.fdrn9999.marketplace.awsapi;

import java.time.Instant;

/**
 * [Mock 정책] Marketplace 구독 이벤트 계약.
 * 실제 AWS는 EventBridge 이벤트(detail-type)나 SNS 메시지(action)로 알려주며 이름과 형식이 서로 다르다.
 * 이 과제에서는 하나의 형식으로 통일했고, 실제 이벤트와의 대응은 README에 정리한다.
 */
public record MarketplaceEvent(
        Type type,
        String licenseArn,
        String customerAWSAccountId,
        String productCode,
        Boolean freeTrial,
        Instant occurredAt) {

    public enum Type {
        /** 구독/계약 시작 (QuickStart: "구독 완료 이벤트" → GetEntitlements) */
        SUBSCRIPTION_STARTED,
        /** 계약 변경/갱신 → GetEntitlements로 재동기화 */
        ENTITLEMENT_UPDATED,
        /** 구독 해지 */
        SUBSCRIPTION_CANCELLED
    }
}
