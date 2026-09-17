package io.github.fdrn9999.marketplace.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.access.EntitlementGuard;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.metering.MeteringBuckets;
import io.github.fdrn9999.marketplace.store.IdempotencyRepository;
import io.github.fdrn9999.marketplace.store.MeteringRecordRepository;
import io.github.fdrn9999.marketplace.store.UsageEventRepository;
import io.github.fdrn9999.marketplace.subscription.EntitlementService;
import io.github.fdrn9999.marketplace.subscription.SubscriptionContext;
import io.github.fdrn9999.marketplace.subscription.SubscriptionStatusCalculator;

/**
 * 권한 검사(락 밖)와 사용량 기록(락 안) 사이에 해지 이벤트가 끼어드는 경합.
 * 가드가 ACTIVE를 돌려준 직후 구독이 해지되는 상황을 결정적으로 재현한다.
 */
class UsageRaceTest {

    @Test
    @DisplayName("권한 확인 직후 구독이 해지되면 락 안에서 다시 검사해 사용량을 기록하지 않는다")
    void recheckInsideLock() {
        Instant now = Instant.parse("2026-09-17T05:30:00Z");
        SimulatedClock clock = new SimulatedClock(Clock.fixed(now, ZoneOffset.UTC));
        Product product = new Product("prod-usage", "사용량형", "", PricingModel.SUBSCRIPTION, null,
                List.of(new Product.Dimension("analysis_run", "분석", "회", BigDecimal.ONE)));
        Subscriber subscriber = new Subscriber();
        subscriber.setSubscriberId("sub-1");
        subscriber.setLicenseArn("arn:1");
        subscriber.setSuccessfullyRegistered(true);
        subscriber.setSuccessfullySubscribed(true);

        // 가드는 ACTIVE를 확인한 뒤 반환하지만, 반환 직전에 해지 이벤트가 처리된다
        EntitlementGuard racingGuard = new EntitlementGuard(null) {
            @Override
            public SubscriptionContext requireActive(String customerId) {
                SubscriptionContext context = new SubscriptionContext(customerId, subscriber, product,
                        new EntitlementService.Freshness(true, false, null),
                        SubscriptionStatusCalculator.evaluate(subscriber, product, true, now));
                subscriber.setSubscriptionExpired(true);
                subscriber.setExpiredReason(StatusReason.UNSUBSCRIBED);
                return context;
            }
        };
        UsageEventRepository usageEvents = new UsageEventRepository();
        UsageService service = new UsageService(racingGuard, usageEvents,
                new MeteringBuckets(new MeteringRecordRepository()), new IdempotencyRepository(), new KeyedLocks(), clock);

        assertThatThrownBy(() -> service.runAnalysis("sub-1", 1, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.SUBSCRIPTION_EXPIRED);
        assertThat(usageEvents.findBySubscriber("sub-1")).isEmpty();
    }
}
