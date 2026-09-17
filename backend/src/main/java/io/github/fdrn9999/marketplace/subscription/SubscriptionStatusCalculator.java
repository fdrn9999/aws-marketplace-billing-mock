package io.github.fdrn9999.marketplace.subscription;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.SubscriptionStatus;

/**
 * 구독 상태 계산 (부수 효과 없는 순수 함수).
 *
 * <p>위에서부터 먼저 걸리는 규칙을 적용한다.
 * <ol>
 *   <li>구독자 없음 → NOT_SUBSCRIBED / NOT_REGISTERED</li>
 *   <li>subscriptionExpired → EXPIRED / (UNSUBSCRIBED | METERING_REJECTED)</li>
 *   <li>successfullySubscribed=false → NOT_SUBSCRIBED / SUBSCRIPTION_PENDING</li>
 *   <li>계약형인데 신뢰할 수 있는 Entitlement 캐시가 없음 → NOT_SUBSCRIBED / ENTITLEMENT_UNVERIFIED (fail-closed)</li>
 *   <li>계약형인데 유효한 Entitlement가 0개 → EXPIRED / CONTRACT_EXPIRED</li>
 *   <li>그 외 → ACTIVE / ENTITLED</li>
 * </ol>
 */
public final class SubscriptionStatusCalculator {

    private SubscriptionStatusCalculator() {
    }

    /**
     * @param entitlementsTrusted 캐시된 Entitlement를 믿을 수 있는지 (한 번 이상 동기화했고 최대 허용 기간 이내)
     */
    public static StatusEvaluation evaluate(Subscriber subscriber, Product product, boolean entitlementsTrusted,
            Instant now) {
        if (subscriber == null || product == null) {
            return of(SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.NOT_REGISTERED);
        }
        if (subscriber.isSubscriptionExpired()) {
            StatusReason reason = subscriber.getExpiredReason() == null ? StatusReason.UNSUBSCRIBED
                    : subscriber.getExpiredReason();
            return of(SubscriptionStatus.EXPIRED, reason);
        }
        if (!subscriber.isSuccessfullySubscribed()) {
            return of(SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.SUBSCRIPTION_PENDING);
        }
        if (!product.pricingModel().usesEntitlements()) {
            return of(SubscriptionStatus.ACTIVE, StatusReason.ENTITLED);
        }
        if (!entitlementsTrusted) {
            return of(SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.ENTITLEMENT_UNVERIFIED);
        }
        List<EntitlementSnapshot> active = subscriber.getEntitlements().stream()
                .filter(e -> e.isActiveAt(now))
                .toList();
        if (active.isEmpty()) {
            return of(SubscriptionStatus.EXPIRED, StatusReason.CONTRACT_EXPIRED);
        }
        Instant expiresAt = active.stream()
                .map(EntitlementSnapshot::expirationDate)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new StatusEvaluation(SubscriptionStatus.ACTIVE, StatusReason.ENTITLED, active, expiresAt);
    }

    private static StatusEvaluation of(SubscriptionStatus status, StatusReason reason) {
        return new StatusEvaluation(status, reason, List.of(), null);
    }
}
