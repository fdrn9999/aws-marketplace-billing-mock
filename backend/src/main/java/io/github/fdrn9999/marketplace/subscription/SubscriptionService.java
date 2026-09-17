package io.github.fdrn9999.marketplace.subscription;

import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.client.MarketplaceApiException;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.ProductRepository;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;

/** 과제 흐름의 "① Subscription 정보 확인" 단계. */
@Service
public class SubscriptionService {

    private final SubscriberRepository subscribers;
    private final ProductRepository products;
    private final EntitlementService entitlements;
    private final SimulatedClock clock;

    public SubscriptionService(SubscriberRepository subscribers, ProductRepository products,
            EntitlementService entitlements, SimulatedClock clock) {
        this.subscribers = subscribers;
        this.products = products;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    /**
     * 고객의 현재 구독 상태를 계산한다. 등록되지 않은 고객(또는 등록 폼을 아직 제출하지 않은 고객)은
     * 오류가 아니라 NOT_SUBSCRIBED 상태로 돌려준다.
     */
    public SubscriptionContext load(String customerId) {
        Subscriber subscriber = subscribers.findById(customerId)
                .filter(Subscriber::isSuccessfullyRegistered)
                .orElse(null);
        if (subscriber == null) {
            return new SubscriptionContext(customerId, null, null, EntitlementService.Freshness.NOT_APPLICABLE,
                    SubscriptionStatusCalculator.evaluate(null, null, false, clock.now()));
        }
        Product product = products.findByCode(subscriber.getProductCode())
                .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PRODUCT));
        EntitlementService.Freshness freshness = entitlements.ensureFresh(subscriber, product);
        StatusEvaluation evaluation = SubscriptionStatusCalculator.evaluate(subscriber, product,
                freshness.trusted(), clock.now());
        return new SubscriptionContext(customerId, subscriber, product, freshness, evaluation);
    }

    /** 캐시 나이와 상관없이 GetEntitlements로 즉시 재동기화한다. */
    public SubscriptionContext refresh(String customerId) {
        SubscriptionContext context = load(customerId);
        if (!context.isRegistered() || !context.product().pricingModel().usesEntitlements()) {
            return context;
        }
        try {
            entitlements.sync(context.subscriber(), context.product());
        } catch (MarketplaceApiException e) {
            throw new ApiException(ErrorCode.ENTITLEMENT_UNAVAILABLE,
                    ErrorCode.ENTITLEMENT_UNAVAILABLE.defaultMessage() + " (" + e.awsErrorType() + ")",
                    java.util.Map.of("awsErrorType", e.awsErrorType()), 30L);
        }
        return load(customerId);
    }
}
