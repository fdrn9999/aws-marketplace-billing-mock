package io.github.fdrn9999.marketplace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import io.github.fdrn9999.marketplace.support.HttpTestSupport;

/** 구매 → Fulfillment URL → ResolveCustomer → 등록 → 구독 상태까지의 온보딩 흐름. */
class OnboardingFlowTest extends HttpTestSupport {

    Response purchase(String productCode, boolean deliverEvent) {
        Response res = post("/mock-aws/_sim/purchase",
                Map.of("productCode", productCode, "deliverSubscriptionEvent", deliverEvent));
        assertThat(res.status()).isEqualTo(200);
        return res;
    }

    /** 구매자 브라우저가 하는 form POST를 흉내 낸다. 302 Location을 돌려준다. */
    String fulfill(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (token != null) {
            form.add("x-amzn-marketplace-token", token);
        }
        var entity = http.post().uri("/marketplace/fulfillment")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toEntity(String.class);
        assertThat(entity.getStatusCode().value()).isEqualTo(302);
        return entity.getHeaders().getLocation().toString();
    }

    Map<String, Object> form(String onboardingId) {
        return Map.of("onboardingId", onboardingId, "companyName", "테스트 주식회사", "contactPerson", "홍길동",
                "contactPhone", "010-1234-5678", "contactEmail", "owner@test.example");
    }

    static String onboardingId(String location) {
        assertThat(location).startsWith("/register?onboarding=");
        return location.substring("/register?onboarding=".length());
    }

    @Test
    @DisplayName("사용량형: 구매 → 302 등록 화면 → 등록 → ACTIVE → 보호 기능 사용 → PENDING 미터링 버킷")
    void usageProductHappyPath() {
        Response purchase = purchase("prod-usage-001", true);
        String id = onboardingId(fulfill(purchase.text("/registrationToken")));

        Response session = get("/api/onboarding/" + id);
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.text("/customerAWSAccountId")).isEqualTo(purchase.text("/customerAWSAccountId"));
        assertThat(session.text("/pricingModel")).isEqualTo("SUBSCRIPTION");
        assertThat(session.body().get("alreadyRegistered").asBoolean()).isFalse();

        Response registered = post("/api/subscribers", form(id));
        assertThat(registered.status()).isEqualTo(201);
        String subscriberId = registered.text("/subscriberId");
        assertThat(registered.text("/subscription/status")).isEqualTo("ACTIVE");

        Response run = post("/api/features/analysis/run", Map.of("dataGb", 3), customer(subscriberId));
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.body().at("/usage/0/meteredQuantity").asLong()).isEqualTo(1);
        assertThat(run.body().at("/usage/1/dimension").asString()).isEqualTo("data_gb");
        assertThat(run.body().at("/usage/1/meteredQuantity").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("계약형: 등록 시 GetEntitlements로 계약 수량(100)을 가져오고 사용량은 계약에서 차감")
    void contractProductSyncsEntitlements() {
        Response purchase = purchase("prod-contract-001", true);
        String id = onboardingId(fulfill(purchase.text("/registrationToken")));
        Response registered = post("/api/subscribers", form(id));

        assertThat(registered.text("/subscription/status")).isEqualTo("ACTIVE");
        assertThat(registered.body().at("/subscription/entitlements/0/quantity").asInt()).isEqualTo(100);
        assertThat(registered.body().at("/subscription/daysUntilExpiry").asLong()).isBetween(364L, 365L);

        Response run = post("/api/features/analysis/run", null, customer(registered.text("/subscriberId")));
        assertThat(run.body().at("/usage/0/includedQuantity").asLong()).isEqualTo(1);
        assertThat(run.body().at("/usage/0/meteredQuantity").asLong()).isZero();
        assertThat(run.body().at("/usage/0/includedRemaining").asLong()).isEqualTo(99);
    }

    @Test
    @DisplayName("구독 이벤트가 오기 전 등록하면 NOT_SUBSCRIBED(SUBSCRIPTION_PENDING), 이벤트 후 ACTIVE")
    void registrationBeforeSubscriptionEvent() {
        Response purchase = purchase("prod-usage-001", false);
        String id = onboardingId(fulfill(purchase.text("/registrationToken")));
        Response registered = post("/api/subscribers", form(id));
        String subscriberId = registered.text("/subscriberId");

        assertThat(registered.text("/subscription/status")).isEqualTo("NOT_SUBSCRIBED");
        assertThat(registered.text("/subscription/reason")).isEqualTo("SUBSCRIPTION_PENDING");
        assertThat(post("/api/features/analysis/run", null, customer(subscriberId)).text("/error/code"))
                .isEqualTo("NOT_SUBSCRIBED");

        Response event = post("/mock-aws/_sim/events",
                Map.of("type", "SUBSCRIPTION_STARTED", "licenseArn", purchase.text("/licenseArn")));
        assertThat(event.body().get("delivered").asBoolean()).isTrue();
        assertThat(get("/api/me/subscription", customer(subscriberId)).text("/status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("토큰 오류는 오류 코드와 함께 등록 화면으로 리디렉션")
    void tokenErrorsRedirectWithCode() {
        assertThat(fulfill("demo-expired-token")).isEqualTo("/register?error=EXPIRED_REGISTRATION_TOKEN");
        assertThat(fulfill("no-such-token")).isEqualTo("/register?error=INVALID_REGISTRATION_TOKEN");
        assertThat(fulfill(null)).isEqualTo("/register?error=INVALID_REGISTRATION_TOKEN");

        String token = purchase("prod-usage-001", true).text("/registrationToken");
        fulfill(token);
        assertThat(fulfill(token)).as("같은 토큰 재제출").isEqualTo("/register?error=EXPIRED_REGISTRATION_TOKEN");
    }

    @Test
    @DisplayName("ResolveCustomer 장애가 재시도 한도를 넘으면 MARKETPLACE_UNAVAILABLE, 한 번이면 재시도로 성공")
    void resolveCustomerRetries() {
        String token = purchase("prod-usage-001", true).text("/registrationToken");
        put("/mock-aws/_sim/faults", Map.of("api", "RESOLVE_CUSTOMER", "mode", "THROTTLE", "remaining", 1));
        assertThat(fulfill(token)).startsWith("/register?onboarding=");

        String token2 = purchase("prod-usage-001", true).text("/registrationToken");
        put("/mock-aws/_sim/faults", Map.of("api", "RESOLVE_CUSTOMER", "mode", "INTERNAL_ERROR", "remaining", -1));
        assertThat(fulfill(token2)).isEqualTo("/register?error=MARKETPLACE_UNAVAILABLE");
    }

    @Test
    @DisplayName("등록 폼 검증 오류, 완료된 세션 재사용, 없는 세션")
    void registrationValidation() {
        String id = onboardingId(fulfill(purchase("prod-usage-001", true).text("/registrationToken")));
        Response invalid = post("/api/subscribers", Map.of("onboardingId", id, "companyName", "",
                "contactPerson", "홍길동", "contactPhone", "abc", "contactEmail", "not-an-email"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.text("/error/code")).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.body().at("/error/details/fields").propertyNames())
                .contains("companyName", "contactPhone", "contactEmail");

        assertThat(post("/api/subscribers", form(id)).status()).isEqualTo(201);
        Response reused = post("/api/subscribers", form(id));
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.text("/error/code")).isEqualTo("ONBOARDING_SESSION_EXPIRED");

        assertThat(get("/api/onboarding/onb-nope").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("같은 라이선스로 다시 등록하면 기존 구독자를 갱신한다 (alreadyRegistered)")
    void reRegistrationIsIdempotent() {
        Response purchase = purchase("prod-usage-001", true);
        String first = post("/api/subscribers", form(onboardingId(fulfill(purchase.text("/registrationToken")))))
                .text("/subscriberId");

        // 구매자가 "계정 설정"을 다시 누르면 AWS는 같은 라이선스에 새 토큰을 발급한다
        String newToken = post("/mock-aws/_sim/tokens", Map.of("licenseArn", purchase.text("/licenseArn")))
                .text("/registrationToken");
        String id = onboardingId(fulfill(newToken));
        assertThat(get("/api/onboarding/" + id).body().get("alreadyRegistered").asBoolean()).isTrue();

        Response again = post("/api/subscribers", Map.of("onboardingId", id, "companyName", "바뀐 회사명",
                "contactPerson", "홍길동", "contactPhone", "010-1234-5678", "contactEmail", "owner@test.example"));
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.text("/subscriberId")).isEqualTo(first);
        assertThat(again.body().get("alreadyRegistered").asBoolean()).isTrue();
        assertThat(again.text("/subscription/companyName")).isEqualTo("바뀐 회사명");
    }

    @Test
    @DisplayName("이벤트 수신 API는 비밀값이 틀리면 401")
    void eventEndpointRequiresSecret() {
        Response res = post("/api/internal/marketplace-events",
                Map.of("type", "SUBSCRIPTION_CANCELLED", "licenseArn", "x", "productCode", "prod-usage-001"),
                Map.of("X-Marketplace-Event-Secret", "wrong"));
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.text("/error/code")).isEqualTo("UNAUTHENTICATED");
    }
}
