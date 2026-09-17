package io.github.fdrn9999.marketplace.subscription;

import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.Subscriber;

/**
 * 한 고객에 대해 "누구인지 → 어떤 상품인지 → 캐시를 믿을 수 있는지 → 상태가 무엇인지"를 모은 결과.
 * subscriber와 product는 미등록 고객이면 null이다.
 */
public record SubscriptionContext(
        String customerId,
        Subscriber subscriber,
        Product product,
        EntitlementService.Freshness freshness,
        StatusEvaluation evaluation) {

    public boolean isRegistered() {
        return subscriber != null;
    }
}
