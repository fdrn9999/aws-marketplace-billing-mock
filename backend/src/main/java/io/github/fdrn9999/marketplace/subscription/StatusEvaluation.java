package io.github.fdrn9999.marketplace.subscription;

import java.time.Instant;
import java.util.List;

import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.SubscriptionStatus;

/**
 * 구독 상태 계산 결과.
 *
 * @param activeEntitlements 현재 유효한 Entitlement (계약형만 해당)
 * @param expiresAt          유효한 Entitlement 중 가장 늦은 만료 시각 (계약형만 해당)
 */
public record StatusEvaluation(
        SubscriptionStatus status,
        StatusReason reason,
        List<EntitlementSnapshot> activeEntitlements,
        Instant expiresAt) {

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }
}
