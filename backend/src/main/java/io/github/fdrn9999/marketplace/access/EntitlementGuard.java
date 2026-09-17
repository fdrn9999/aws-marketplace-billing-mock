package io.github.fdrn9999.marketplace.access;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.subscription.StatusEvaluation;
import io.github.fdrn9999.marketplace.subscription.SubscriptionContext;
import io.github.fdrn9999.marketplace.subscription.SubscriptionService;

/**
 * 과제 흐름의 "② 사용 권한 확인" 단계. 보호 기능을 실행하기 전에 호출한다.
 *
 * <pre>
 * ACTIVE          → 통과 (계약 수량 검사는 사용량 기록 시 차원별로 수행)
 * EXPIRED         → 403 SUBSCRIPTION_EXPIRED
 * NOT_SUBSCRIBED  → 403 NOT_SUBSCRIBED
 * 계약 정보 확인 불가(GetEntitlements 장애 + 캐시 사용 불가) → 503 ENTITLEMENT_UNAVAILABLE
 * </pre>
 */
@Component
public class EntitlementGuard {

    private final SubscriptionService subscriptions;

    public EntitlementGuard(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    public SubscriptionContext requireActive(String customerId) {
        SubscriptionContext context = subscriptions.load(customerId);
        if (context.freshness().stale() && !context.freshness().trusted()) {
            throw new ApiException(ErrorCode.ENTITLEMENT_UNAVAILABLE,
                    ErrorCode.ENTITLEMENT_UNAVAILABLE.defaultMessage() + " 잠시 후 다시 시도해 주세요.",
                    Map.of("awsErrorType", String.valueOf(context.freshness().error())), 30L);
        }
        StatusEvaluation evaluation = context.evaluation();
        switch (evaluation.status()) {
            case ACTIVE:
                return context;
            case EXPIRED:
                throw new ApiException(ErrorCode.SUBSCRIPTION_EXPIRED, ErrorCode.SUBSCRIPTION_EXPIRED.defaultMessage(),
                        Map.of("reason", evaluation.reason().name()));
            default:
                throw new ApiException(ErrorCode.NOT_SUBSCRIBED, ErrorCode.NOT_SUBSCRIBED.defaultMessage(),
                        Map.of("reason", evaluation.reason().name()));
        }
    }
}
