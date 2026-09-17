package io.github.fdrn9999.marketplace.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.SubscriptionStatus;

class SubscriptionStatusCalculatorTest {

    static final Instant NOW = Instant.parse("2026-09-17T05:00:00Z");

    static Product product(PricingModel model) {
        return new Product("p", "p", "", model, BigDecimal.ONE,
                List.of(new Product.Dimension("analysis_run", "분석", "회", BigDecimal.ONE)));
    }

    static Subscriber subscriber(boolean subscribed, boolean expired, StatusReason expiredReason,
            List<EntitlementSnapshot> entitlements) {
        Subscriber s = new Subscriber();
        s.setSubscriberId("s");
        s.setLicenseArn("arn");
        s.setSuccessfullyRegistered(true);
        s.setSuccessfullySubscribed(subscribed);
        s.setSubscriptionExpired(expired);
        s.setExpiredReason(expiredReason);
        s.setEntitlements(entitlements);
        return s;
    }

    static EntitlementSnapshot ent(Duration fromNow) {
        return new EntitlementSnapshot("analysis_run", 100, NOW.plus(fromNow));
    }

    static Stream<Arguments> rules() {
        List<EntitlementSnapshot> valid = List.of(ent(Duration.ofDays(3)));
        List<EntitlementSnapshot> expired = List.of(ent(Duration.ofDays(-1)));
        List<EntitlementSnapshot> partlyExpired = List.of(ent(Duration.ofDays(-1)),
                new EntitlementSnapshot("data_gb", 10, NOW.plus(Duration.ofDays(10))));
        return Stream.of(
                // 설명, 구독자, 모델, 캐시 신뢰, 기대 상태, 기대 사유
                Arguments.of("구독자 없음", null, PricingModel.SUBSCRIPTION, true,
                        SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.NOT_REGISTERED),
                Arguments.of("해지됨(사유 지정)", subscriber(true, true, StatusReason.UNSUBSCRIBED, List.of()),
                        PricingModel.SUBSCRIPTION, true, SubscriptionStatus.EXPIRED, StatusReason.UNSUBSCRIBED),
                Arguments.of("미터링 거절로 만료", subscriber(true, true, StatusReason.METERING_REJECTED, List.of()),
                        PricingModel.SUBSCRIPTION, true, SubscriptionStatus.EXPIRED, StatusReason.METERING_REJECTED),
                Arguments.of("해지가 구독 대기보다 우선", subscriber(false, true, null, List.of()),
                        PricingModel.SUBSCRIPTION, true, SubscriptionStatus.EXPIRED, StatusReason.UNSUBSCRIBED),
                Arguments.of("구독 이벤트 대기", subscriber(false, false, null, List.of()),
                        PricingModel.SUBSCRIPTION, true, SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.SUBSCRIPTION_PENDING),
                Arguments.of("사용량형 정상", subscriber(true, false, null, List.of()),
                        PricingModel.SUBSCRIPTION, false, SubscriptionStatus.ACTIVE, StatusReason.ENTITLED),
                Arguments.of("계약형 캐시 불신(fail-closed)", subscriber(true, false, null, valid),
                        PricingModel.CONTRACT, false, SubscriptionStatus.NOT_SUBSCRIBED, StatusReason.ENTITLEMENT_UNVERIFIED),
                Arguments.of("계약형 전부 만료", subscriber(true, false, null, expired),
                        PricingModel.CONTRACT, true, SubscriptionStatus.EXPIRED, StatusReason.CONTRACT_EXPIRED),
                Arguments.of("계약형 Entitlement 없음", subscriber(true, false, null, List.of()),
                        PricingModel.CONTRACT, true, SubscriptionStatus.EXPIRED, StatusReason.CONTRACT_EXPIRED),
                Arguments.of("계약형 정상", subscriber(true, false, null, valid),
                        PricingModel.CONTRACT, true, SubscriptionStatus.ACTIVE, StatusReason.ENTITLED),
                Arguments.of("혼합형 일부 차원만 만료 → ACTIVE", subscriber(true, false, null, partlyExpired),
                        PricingModel.CONTRACT_WITH_SUBSCRIPTION, true, SubscriptionStatus.ACTIVE, StatusReason.ENTITLED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    @DisplayName("상태 계산 규칙표")
    void evaluatesRules(String description, Subscriber subscriber, PricingModel model, boolean trusted,
            SubscriptionStatus expectedStatus, StatusReason expectedReason) {
        StatusEvaluation result = SubscriptionStatusCalculator.evaluate(subscriber, product(model), trusted, NOW);
        assertThat(result.status()).as(description).isEqualTo(expectedStatus);
        assertThat(result.reason()).as(description).isEqualTo(expectedReason);
    }

    @Test
    @DisplayName("유효한 Entitlement만 모으고 가장 늦은 만료일을 expiresAt으로 쓴다")
    void collectsActiveEntitlements() {
        Subscriber s = subscriber(true, false, null, List.of(ent(Duration.ofDays(-1)), ent(Duration.ofDays(2)),
                ent(Duration.ofDays(5))));
        StatusEvaluation result = SubscriptionStatusCalculator.evaluate(s, product(PricingModel.CONTRACT), true, NOW);
        assertThat(result.activeEntitlements()).hasSize(2);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
    }

    @Test
    @DisplayName("만료 시각과 정확히 같은 순간은 만료로 본다")
    void expirationBoundaryIsExclusive() {
        Subscriber s = subscriber(true, false, null, List.of(ent(Duration.ZERO)));
        assertThat(SubscriptionStatusCalculator.evaluate(s, product(PricingModel.CONTRACT), true, NOW).status())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    @DisplayName("갱신 판정: 같은 차원의 만료일이 늘어나면 갱신")
    void detectsRenewal() {
        List<EntitlementSnapshot> before = List.of(ent(Duration.ofDays(1)));
        assertThat(EntitlementService.isRenewal(before, List.of(ent(Duration.ofDays(365))))).isTrue();
        assertThat(EntitlementService.isRenewal(before, List.of(ent(Duration.ofDays(1))))).isFalse();
        assertThat(EntitlementService.isRenewal(List.of(), List.of(ent(Duration.ofDays(365))))).isFalse();
    }
}
