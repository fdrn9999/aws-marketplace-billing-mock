package io.github.fdrn9999.marketplace.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.support.HttpTestSupport;

/** 시드 고객별 구독 상태와 보호 기능 접근 제어 (과제 흐름 ①②③). */
class AccessControlTest extends HttpTestSupport {

    static final String CONTRACT_ACTIVE_ARN = "arn:aws:license-manager::222222222222:license:l-contract-active";
    static final String CONTRACT_EXPIRED_ARN = "arn:aws:license-manager::444444444444:license:l-contract-expired";
    static final String USAGE_ACTIVE_ARN = "arn:aws:license-manager::111111111111:license:l-usage-active";

    Response run(String customerId) {
        return post("/api/features/analysis/run", Map.of("dataGb", 1), customer(customerId));
    }

    Response status(String customerId) {
        return get("/api/me/subscription", customer(customerId));
    }

    @Test
    @DisplayName("고객 헤더가 없으면 401 UNAUTHENTICATED")
    void requiresCustomerHeader() {
        Response res = post("/api/features/analysis/run", Map.of());
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.text("/error/code")).isEqualTo("UNAUTHENTICATED");
        assertThat(res.text("/error/requestId")).isNotBlank();
        assertThat(get("/api/me/subscription").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("미등록 고객: 상태는 200 NOT_SUBSCRIBED(NOT_REGISTERED), 기능은 403")
    void unregisteredCustomer() {
        Response s = status("guest");
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.text("/status")).isEqualTo("NOT_SUBSCRIBED");
        assertThat(s.text("/reason")).isEqualTo("NOT_REGISTERED");
        assertThat(s.body().get("canUseFeatures").asBoolean()).isFalse();

        Response r = run("guest");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.text("/error/code")).isEqualTo("NOT_SUBSCRIBED");
        assertThat(r.text("/error/details/reason")).isEqualTo("NOT_REGISTERED");
    }

    @Test
    @DisplayName("시드 시나리오별 상태와 접근 결과")
    void seededScenarios() {
        assertThat(status("sub-usage-active").text("/status")).isEqualTo("ACTIVE");
        assertThat(run("sub-usage-active").status()).isEqualTo(200);

        assertThat(status("sub-pending").text("/reason")).isEqualTo("SUBSCRIPTION_PENDING");
        assertThat(run("sub-pending").text("/error/details/reason")).isEqualTo("SUBSCRIPTION_PENDING");

        Response expired = run("sub-contract-expired");
        assertThat(expired.status()).isEqualTo(403);
        assertThat(expired.text("/error/code")).isEqualTo("SUBSCRIPTION_EXPIRED");
        assertThat(expired.text("/error/details/reason")).isEqualTo("CONTRACT_EXPIRED");

        Response unsubscribed = run("sub-unsubscribed");
        assertThat(unsubscribed.text("/error/code")).isEqualTo("SUBSCRIPTION_EXPIRED");
        assertThat(unsubscribed.text("/error/details/reason")).isEqualTo("UNSUBSCRIBED");
    }

    @Test
    @DisplayName("계약형: 100회 중 95회 사용 → 5회 더 허용, 6번째는 403 QUOTA_EXCEEDED")
    void contractQuota() {
        Response contract = status("sub-contract-active");
        assertThat(contract.body().get("expiringSoon").asBoolean()).as("6일 남음 → 만료 임박").isTrue();

        for (int i = 0; i < 5; i++) {
            assertThat(run("sub-contract-active").status()).isEqualTo(200);
        }
        Response over = run("sub-contract-active");
        assertThat(over.status()).isEqualTo(403);
        assertThat(over.text("/error/code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(over.body().at("/error/details/used").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("혼합형: 포함량(50)을 이미 넘었으므로 추가 사용은 전부 초과분으로 미터링")
    void hybridOverage() {
        Response r = run("sub-hybrid-overage");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("overage").asBoolean()).isTrue();
        assertThat(r.body().at("/usage/0/includedQuantity").asLong()).isZero();
        assertThat(r.body().at("/usage/0/meteredQuantity").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("시계를 7일 옮기면 계약이 만료되어 EXPIRED(CONTRACT_EXPIRED)")
    void contractExpiresOverTime() {
        clock.advance(Duration.ofDays(7));
        Response s = status("sub-contract-active");
        assertThat(s.text("/status")).isEqualTo("EXPIRED");
        assertThat(s.text("/reason")).isEqualTo("CONTRACT_EXPIRED");
        assertThat(run("sub-contract-active").text("/error/code")).isEqualTo("SUBSCRIPTION_EXPIRED");
    }

    @Test
    @DisplayName("계약 갱신 이벤트: 만료 고객이 ACTIVE로 돌아오고 새 계약 기간의 사용량은 0부터")
    void renewalResetsTerm() {
        Response event = post("/mock-aws/_sim/events",
                Map.of("type", "ENTITLEMENT_UPDATED", "licenseArn", CONTRACT_EXPIRED_ARN, "renewDays", 30));
        assertThat(event.body().get("delivered").asBoolean()).isTrue();

        assertThat(status("sub-contract-expired").text("/status")).isEqualTo("ACTIVE");
        Response r = run("sub-contract-expired");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().at("/usage/0/includedRemaining").asLong()).isEqualTo(99);
    }

    @Test
    @DisplayName("해지 이벤트: EXPIRED(UNSUBSCRIBED)로 바뀌고 기능이 차단된다")
    void cancellation() {
        post("/mock-aws/_sim/events", Map.of("type", "SUBSCRIPTION_CANCELLED", "licenseArn", USAGE_ACTIVE_ARN));
        Response s = status("sub-usage-active");
        assertThat(s.text("/status")).isEqualTo("EXPIRED");
        assertThat(s.text("/reason")).isEqualTo("UNSUBSCRIBED");
        assertThat(run("sub-usage-active").status()).isEqualTo(403);
    }

    @Test
    @DisplayName("GetEntitlements 장애: 24시간 이내 캐시는 stale로 허용, 넘으면 503(fail-closed)")
    void entitlementOutage() {
        put("/mock-aws/_sim/faults", Map.of("api", "GET_ENTITLEMENTS", "mode", "INTERNAL_ERROR", "remaining", -1));

        clock.advance(Duration.ofHours(1));
        Response stale = status("sub-contract-active");
        assertThat(stale.text("/status")).isEqualTo("ACTIVE");
        assertThat(stale.body().get("stale").asBoolean()).isTrue();
        assertThat(stale.text("/syncError")).isEqualTo("InternalServiceErrorException");
        assertThat(run("sub-contract-active").status()).isEqualTo(200);

        clock.advance(Duration.ofHours(24));
        Response unverified = status("sub-contract-active");
        assertThat(unverified.text("/status")).isEqualTo("NOT_SUBSCRIBED");
        assertThat(unverified.text("/reason")).isEqualTo("ENTITLEMENT_UNVERIFIED");
        Response blocked = run("sub-contract-active");
        assertThat(blocked.status()).isEqualTo(503);
        assertThat(blocked.text("/error/code")).isEqualTo("ENTITLEMENT_UNAVAILABLE");
        assertThat(blocked.headers().getFirst("Retry-After")).isEqualTo("30");

        put("/mock-aws/_sim/faults", Map.of("api", "GET_ENTITLEMENTS", "mode", "NONE"));
        assertThat(run("sub-contract-active").status()).as("장애 해소 후 복구").isEqualTo(200);
    }

    @Test
    @DisplayName("Idempotency-Key: 같은 요청 재전송은 한 번만 기록, 다른 내용이면 409")
    void idempotency() {
        Map<String, String> headers = Map.of("X-Customer-Id", "sub-contract-active", "Idempotency-Key", "k-1");
        Response first = post("/api/features/analysis/run", Map.of("dataGb", 1), headers);
        Response replay = post("/api/features/analysis/run", Map.of("dataGb", 1), headers);

        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        assertThat(replay.text("/runId")).isEqualTo(first.text("/runId"));
        assertThat(replay.body().at("/usage/0/includedRemaining").asLong()).isEqualTo(4);

        Response conflict = post("/api/features/analysis/run", Map.of("dataGb", 2), headers);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.text("/error/code")).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        assertThat(run("sub-contract-active").body().at("/usage/0/includedRemaining").asLong())
                .as("재전송은 차감되지 않았으므로 남은 수량은 3").isEqualTo(3);
    }

    @Test
    @DisplayName("요청 값 오류: dataGb 범위, 잘못된 JSON")
    void badRequests() {
        Response range = post("/api/features/analysis/run", Map.of("dataGb", 0), customer("sub-usage-active"));
        assertThat(range.status()).isEqualTo(400);
        assertThat(range.text("/error/code")).isEqualTo("VALIDATION_ERROR");

        Response malformed = post("/api/features/analysis/run", "{not json", customer("sub-usage-active"));
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.text("/error/code")).isEqualTo("VALIDATION_ERROR");
        assertThat(malformed.text("/error/message"))
                .as("역직렬화 내부 메시지를 노출하지 않는다")
                .isEqualTo("요청 본문을 해석할 수 없습니다 (JSON 형식과 값의 타입을 확인해 주세요)");

        assertThat(get("/api/nope").status()).isEqualTo(404);
    }
}
