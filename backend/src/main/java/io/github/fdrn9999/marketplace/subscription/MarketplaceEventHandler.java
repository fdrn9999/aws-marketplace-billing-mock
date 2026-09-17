package io.github.fdrn9999.marketplace.subscription;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.awsapi.MarketplaceEvent;
import io.github.fdrn9999.marketplace.client.MarketplaceApiException;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.ProductRepository;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;

/**
 * Marketplace 구독 이벤트 처리 (QuickStart: EventBridge → EntitlementSQSQueue → EntitlementSQSHandler).
 * 이벤트는 구매자가 등록 폼을 제출하기 전에 올 수 있으므로, 구독자 레코드가 없으면 미등록 상태로 먼저 만든다.
 * 같은 이벤트가 여러 번 와도 결과가 같도록(멱등) 상태를 덮어쓰는 방식으로 처리한다.
 */
@Service
public class MarketplaceEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEventHandler.class);

    public record HandleResult(String subscriberId, boolean entitlementsSynced, String syncError) {
    }

    private final SubscriberRepository subscribers;
    private final ProductRepository products;
    private final EntitlementService entitlements;
    private final KeyedLocks locks;
    private final SimulatedClock clock;

    public MarketplaceEventHandler(SubscriberRepository subscribers, ProductRepository products,
            EntitlementService entitlements, KeyedLocks locks, SimulatedClock clock) {
        this.subscribers = subscribers;
        this.products = products;
        this.entitlements = entitlements;
        this.locks = locks;
        this.clock = clock;
    }

    public HandleResult handle(MarketplaceEvent event) {
        if (event == null || event.type() == null || event.licenseArn() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "type과 licenseArn은 필수입니다");
        }
        Product product = products.findByCode(event.productCode())
                .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PRODUCT));

        Subscriber subscriber = locks.withLock(event.licenseArn(), () -> {
            Instant now = clock.now();
            Subscriber s = subscribers.findByLicenseArn(event.licenseArn())
                    .orElseGet(() -> newUnregistered(event, now));
            switch (event.type()) {
                case SUBSCRIPTION_STARTED -> {
                    s.setSuccessfullySubscribed(true);
                    s.setSubscriptionExpired(false);
                    s.setExpiredReason(null);
                    s.setFreeTrialTermPresent(Boolean.TRUE.equals(event.freeTrial()));
                    if (s.getTermStartAt() == null) {
                        s.setTermStartAt(now);
                    }
                }
                case ENTITLEMENT_UPDATED -> {
                    // 계약 변경/갱신: 아래에서 GetEntitlements로 재동기화한다
                    s.setSubscriptionExpired(false);
                    s.setExpiredReason(null);
                }
                case SUBSCRIPTION_CANCELLED -> {
                    s.setSubscriptionExpired(true);
                    s.setExpiredReason(StatusReason.UNSUBSCRIBED);
                }
                default -> throw new IllegalStateException("지원하지 않는 이벤트: " + event.type());
            }
            s.setUpdatedAt(now);
            return subscribers.save(s);
        });

        boolean synced = false;
        String syncError = null;
        if (product.pricingModel().usesEntitlements() && event.type() != MarketplaceEvent.Type.SUBSCRIPTION_CANCELLED) {
            try {
                entitlements.sync(subscriber, product);
                synced = true;
            } catch (MarketplaceApiException e) {
                // 조회 실패는 다음 상태 조회 때 다시 시도한다 (캐시 정책 참고)
                syncError = e.awsErrorType();
                log.warn("이벤트 처리 중 Entitlement 동기화 실패: {} {}", event.licenseArn(), e.awsErrorType());
            }
        }
        log.info("Marketplace 이벤트 처리: {} {} → {}", event.type(), event.licenseArn(), subscriber.getSubscriberId());
        return new HandleResult(subscriber.getSubscriberId(), synced, syncError);
    }

    private static Subscriber newUnregistered(MarketplaceEvent event, Instant now) {
        Subscriber s = new Subscriber();
        s.setSubscriberId(Ids.next("sub"));
        s.setLicenseArn(event.licenseArn());
        s.setCustomerAWSAccountId(event.customerAWSAccountId());
        s.setProductCode(event.productCode());
        s.setSuccessfullyRegistered(false);
        s.setCreatedAt(now);
        return s;
    }
}
