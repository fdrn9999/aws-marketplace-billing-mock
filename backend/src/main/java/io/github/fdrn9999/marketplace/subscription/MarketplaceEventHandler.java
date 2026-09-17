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
 * SQS는 순서를 보장하지 않으므로, 이미 반영한 이벤트보다 먼저 발생한 이벤트는 무시한다.
 */
@Service
public class MarketplaceEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEventHandler.class);

    /** @param ignored 이미 반영한 이벤트보다 오래된 이벤트라서 무시했는지 */
    public record HandleResult(String subscriberId, boolean ignored, boolean entitlementsSynced, String syncError) {
    }

    /** 락 안에서 결정한 처리 결과 */
    private record Applied(Subscriber subscriber, boolean ignored) {
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

        Applied applied = locks.withLock(event.licenseArn(), () -> {
            Instant now = clock.now();
            Instant occurredAt = event.occurredAt() == null ? now : event.occurredAt();
            Subscriber s = subscribers.findByLicenseArn(event.licenseArn())
                    .orElseGet(() -> newUnregistered(event, now));
            if (s.getLastEventAt() != null && occurredAt.isBefore(s.getLastEventAt())) {
                return new Applied(s, true);
            }
            switch (event.type()) {
                case SUBSCRIPTION_STARTED -> {
                    // 해지됐던 구독이 다시 시작되면 새 계약 기간으로 보고 사용량을 0부터 센다
                    if (s.getTermStartAt() == null || s.isSubscriptionExpired()) {
                        s.setTermStartAt(now);
                    }
                    s.setSuccessfullySubscribed(true);
                    s.setSubscriptionExpired(false);
                    s.setExpiredReason(null);
                    s.setFreeTrialTermPresent(Boolean.TRUE.equals(event.freeTrial()));
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
            s.setLastEventAt(occurredAt);
            s.setUpdatedAt(now);
            return new Applied(subscribers.save(s), false);
        });
        Subscriber subscriber = applied.subscriber();
        if (applied.ignored()) {
            log.info("오래된 Marketplace 이벤트 무시: {} {} (발생 {}, 마지막 반영 {})", event.type(), event.licenseArn(),
                    event.occurredAt(), subscriber.getLastEventAt());
            return new HandleResult(subscriber.getSubscriberId(), true, false, null);
        }

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
        return new HandleResult(subscriber.getSubscriberId(), false, synced, syncError);
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
