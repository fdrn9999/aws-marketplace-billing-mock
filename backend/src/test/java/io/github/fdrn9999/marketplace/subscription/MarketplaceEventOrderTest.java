package io.github.fdrn9999.marketplace.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.support.HttpTestSupport;

/** 구독 이벤트의 순서 역전과 재구독 처리. */
class MarketplaceEventOrderTest extends HttpTestSupport {

    static final String CONTRACT_ACTIVE_ARN = "arn:aws:license-manager::222222222222:license:l-contract-active";
    static final String HYBRID_ARN = "arn:aws:license-manager::333333333333:license:l-hybrid-overage";
    static final Map<String, String> SECRET = Map.of("X-Marketplace-Event-Secret", "local-dev-event-secret");

    Response sendEvent(String type, String licenseArn, String productCode, String occurredAt) {
        return post("/api/internal/marketplace-events", Map.of("type", type, "licenseArn", licenseArn,
                "customerAWSAccountId", licenseArn.split(":")[4], "productCode", productCode,
                "occurredAt", occurredAt), SECRET);
    }

    @Test
    @DisplayName("해지 뒤에 도착한 더 오래된 계약 갱신 이벤트는 무시한다 (SQS는 순서를 보장하지 않음)")
    void ignoresOutOfOrderEvent() {
        String now = clock.now().toString();
        String earlier = clock.now().minus(Duration.ofMinutes(10)).toString();

        Response cancelled = sendEvent("SUBSCRIPTION_CANCELLED", CONTRACT_ACTIVE_ARN, "prod-contract-001", now);
        assertThat(cancelled.status()).isEqualTo(200);

        Response stale = sendEvent("ENTITLEMENT_UPDATED", CONTRACT_ACTIVE_ARN, "prod-contract-001", earlier);
        assertThat(stale.status()).isEqualTo(200);
        assertThat(stale.body().get("ignored").asBoolean()).isTrue();

        Response status = get("/api/me/subscription", customer("sub-contract-active"));
        assertThat(status.text("/status")).isEqualTo("EXPIRED");
        assertThat(status.text("/reason")).isEqualTo("UNSUBSCRIBED");
    }

    @Test
    @DisplayName("같은 시각의 중복 이벤트는 다시 처리해도 결과가 같다")
    void duplicateEventIsIdempotent() {
        String now = clock.now().toString();
        assertThat(sendEvent("SUBSCRIPTION_CANCELLED", CONTRACT_ACTIVE_ARN, "prod-contract-001", now)
                .body().get("ignored").asBoolean()).isFalse();
        assertThat(sendEvent("SUBSCRIPTION_CANCELLED", CONTRACT_ACTIVE_ARN, "prod-contract-001", now)
                .body().get("ignored").asBoolean()).isFalse();
        assertThat(get("/api/me/subscription", customer("sub-contract-active")).text("/status")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("해지 후 다시 구독하면 새 계약 기간이 시작되어 이전 사용량을 차감하지 않는다")
    void resubscribeStartsNewTerm() {
        // 혼합형 고객은 포함량 50을 모두 썼다
        assertThat(post("/api/features/analysis/run", null, customer("sub-hybrid-overage"))
                .body().get("overage").asBoolean()).isTrue();

        post("/mock-aws/_sim/events", Map.of("type", "SUBSCRIPTION_CANCELLED", "licenseArn", HYBRID_ARN));
        clock.advance(Duration.ofMinutes(1));
        post("/mock-aws/_sim/events", Map.of("type", "SUBSCRIPTION_STARTED", "licenseArn", HYBRID_ARN));

        Response run = post("/api/features/analysis/run", null, customer("sub-hybrid-overage"));
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.body().at("/usage/0/includedQuantity").asLong()).isEqualTo(1);
        assertThat(run.body().at("/usage/0/includedRemaining").asLong()).isEqualTo(49);
        assertThat(run.body().get("overage").asBoolean()).isFalse();
    }
}
