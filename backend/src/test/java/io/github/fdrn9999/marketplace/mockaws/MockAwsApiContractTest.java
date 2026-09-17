package io.github.fdrn9999.marketplace.mockaws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.support.HttpTestSupport;
import tools.jackson.databind.JsonNode;

/**
 * Mock AWS API 계약 테스트. 요청/응답 필드는 가이드 p13/p16/p17 형식(PascalCase)을 따른다.
 */
class MockAwsApiContractTest extends HttpTestSupport {

    static final String CONTRACT_ACTIVE = "arn:aws:license-manager::222222222222:license:l-contract-active";
    static final String USAGE_ACTIVE = "arn:aws:license-manager::111111111111:license:l-usage-active";
    static final String UNSUBSCRIBED = "arn:aws:license-manager::555555555555:license:l-unsubscribed";

    String purchaseToken(String productCode) {
        Response purchase = post("/mock-aws/_sim/purchase",
                Map.of("productCode", productCode, "deliverSubscriptionEvent", false));
        assertThat(purchase.status()).isEqualTo(200);
        return purchase.text("/registrationToken");
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.propertyNames().iterator();
        it.forEachRemaining(names::add);
        return names;
    }

    @Nested
    @DisplayName("ResolveCustomer")
    class ResolveCustomer {

        @Test
        @DisplayName("유효한 토큰이면 가이드 p13 형식으로 구매자 정보를 반환한다")
        void resolvesToken() {
            String token = purchaseToken("prod-usage-001");
            Response res = post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", token));

            assertThat(res.status()).isEqualTo(200);
            assertThat(fieldNames(res.body())).containsExactlyInAnyOrder(
                    "CustomerIdentifier", "CustomerAWSAccountId", "ProductCode", "LicenseArn");
            assertThat(res.text("/ProductCode")).isEqualTo("prod-usage-001");
            assertThat(res.text("/CustomerAWSAccountId")).matches("\\d{12}");
            assertThat(res.text("/LicenseArn")).startsWith("arn:aws:license-manager::" + res.text("/CustomerAWSAccountId"));
        }

        @Test
        @DisplayName("같은 토큰을 다시 제출하면 ExpiredTokenException (토큰은 즉시 1회 사용)")
        void rejectsResubmittedToken() {
            String token = purchaseToken("prod-usage-001");
            post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", token));
            Response again = post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", token));

            assertThat(again.status()).isEqualTo(400);
            assertThat(again.text("/__type")).isEqualTo("ExpiredTokenException");
        }

        @Test
        @DisplayName("만료 토큰은 ExpiredTokenException, 없는 토큰·빈 값은 InvalidTokenException")
        void rejectsExpiredAndUnknownTokens() {
            assertThat(post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", "demo-expired-token"))
                    .text("/__type")).isEqualTo("ExpiredTokenException");
            assertThat(post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", "demo-used-token"))
                    .text("/__type")).isEqualTo("ExpiredTokenException");
            assertThat(post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", "nope"))
                    .text("/__type")).isEqualTo("InvalidTokenException");
            assertThat(post("/mock-aws/metering/resolve-customer", Map.of()).text("/__type"))
                    .isEqualTo("InvalidTokenException");
        }

        @Test
        @DisplayName("장애 주입(THROTTLE 1회) 시 ThrottlingException 후 정상 응답")
        void throttlesOnce() {
            String token = purchaseToken("prod-usage-001");
            put("/mock-aws/_sim/faults", Map.of("api", "RESOLVE_CUSTOMER", "mode", "THROTTLE", "remaining", 1));

            Response throttled = post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", token));
            assertThat(throttled.status()).isEqualTo(400);
            assertThat(throttled.text("/__type")).isEqualTo("ThrottlingException");
            assertThat(post("/mock-aws/metering/resolve-customer", Map.of("RegistrationToken", token)).status())
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("GetEntitlements")
    class GetEntitlements {

        @Test
        @DisplayName("LICENSE_ARN 필터로 가이드 p17 형식의 계약 정보를 반환한다")
        void returnsEntitlementByLicense() {
            Response res = post("/mock-aws/entitlement/get-entitlements", Map.of(
                    "ProductCode", "prod-contract-001",
                    "Filter", Map.of("LICENSE_ARN", List.of(CONTRACT_ACTIVE))));

            assertThat(res.status()).isEqualTo(200);
            JsonNode entitlements = res.body().get("Entitlements");
            assertThat(entitlements).hasSize(1);
            JsonNode e = entitlements.get(0);
            assertThat(fieldNames(e)).contains("CustomerIdentifier", "Dimension", "ExpirationDate", "ProductCode",
                    "LicenseArn", "Value");
            assertThat(e.get("Dimension").asString()).isEqualTo("analysis_run");
            assertThat(e.at("/Value/IntegerValue").asInt()).isEqualTo(100);
            long daysLeft = Duration.between(clock.now(), Instant.ofEpochSecond(e.get("ExpirationDate").asLong())).toHours() / 24;
            assertThat(daysLeft).isBetween(5L, 6L);
        }

        @Test
        @DisplayName("가이드 표기 필터 키(LicenseArn)도 허용한다")
        void acceptsGuideFilterKey() {
            Response res = post("/mock-aws/entitlement/get-entitlements", Map.of(
                    "ProductCode", "prod-contract-001",
                    "Filter", Map.of("LicenseArn", List.of(CONTRACT_ACTIVE))));
            assertThat(res.body().get("Entitlements")).hasSize(1);
        }

        @Test
        @DisplayName("MaxResults와 NextToken으로 페이지를 나눈다")
        void paginates() {
            Response first = post("/mock-aws/entitlement/get-entitlements",
                    Map.of("ProductCode", "prod-contract-001", "MaxResults", 1));
            assertThat(first.body().get("Entitlements")).hasSize(1);
            String next = first.text("/NextToken");
            assertThat(next).isNotBlank();

            Response second = post("/mock-aws/entitlement/get-entitlements",
                    Map.of("ProductCode", "prod-contract-001", "MaxResults", 1, "NextToken", next));
            assertThat(second.body().get("Entitlements")).hasSize(1);
            assertThat(second.text("/NextToken")).isNull();
            assertThat(second.body().at("/Entitlements/0/LicenseArn").asString())
                    .isNotEqualTo(first.body().at("/Entitlements/0/LicenseArn").asString());
        }

        @Test
        @DisplayName("조작된 NextToken(음수 offset)은 500이 아니라 InvalidParameterException")
        void rejectsTamperedNextToken() {
            String negative = java.util.Base64.getUrlEncoder()
                    .encodeToString("offset:-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Response res = post("/mock-aws/entitlement/get-entitlements",
                    Map.of("ProductCode", "prod-contract-001", "NextToken", negative));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.text("/__type")).isEqualTo("InvalidParameterException");
        }

        @Test
        @DisplayName("ProductCode 누락, 알 수 없는 필터 키, 잘못된 MaxResults는 InvalidParameterException")
        void rejectsInvalidParameters() {
            assertThat(post("/mock-aws/entitlement/get-entitlements", Map.of()).text("/__type"))
                    .isEqualTo("InvalidParameterException");
            assertThat(post("/mock-aws/entitlement/get-entitlements",
                    Map.of("ProductCode", "prod-contract-001", "Filter", Map.of("FOO", List.of("x")))).text("/__type"))
                    .isEqualTo("InvalidParameterException");
            assertThat(post("/mock-aws/entitlement/get-entitlements",
                    Map.of("ProductCode", "prod-contract-001", "MaxResults", 26)).text("/__type"))
                    .isEqualTo("InvalidParameterException");
        }
    }

    @Nested
    @DisplayName("BatchMeterUsage")
    class BatchMeterUsage {

        Map<String, Object> record(String licenseArn, String account, String dimension, int quantity, Instant at) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("CustomerAWSAccountId", account);
            r.put("Dimension", dimension);
            r.put("Quantity", quantity);
            r.put("Timestamp", at.getEpochSecond());
            r.put("LicenseArn", licenseArn);
            return r;
        }

        Instant lastHour() {
            return clock.currentHourStart().minus(1, ChronoUnit.HOURS);
        }

        @Test
        @DisplayName("성공 시 가이드 p16 형식(Results/UnprocessedRecords)으로 응답하고, 같은 레코드 재전송은 멱등")
        void acceptsAndIsIdempotent() {
            Map<String, Object> body = Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "analysis_run", 3, lastHour())));
            Response first = post("/mock-aws/metering/batch-meter-usage", body);

            assertThat(first.status()).isEqualTo(200);
            assertThat(fieldNames(first.body())).containsExactlyInAnyOrder("Results", "UnprocessedRecords");
            JsonNode result = first.body().at("/Results/0");
            assertThat(result.get("Status").asString()).isEqualTo("Success");
            assertThat(result.get("MeteringRecordId").asString()).isNotBlank();
            assertThat(result.at("/UsageRecord/Quantity").asInt()).isEqualTo(3);

            Response again = post("/mock-aws/metering/batch-meter-usage", body);
            assertThat(again.body().at("/Results/0/Status").asString()).isEqualTo("Success");
            assertThat(again.body().at("/Results/0/MeteringRecordId").asString())
                    .isEqualTo(result.get("MeteringRecordId").asString());
        }

        @Test
        @DisplayName("같은 고객·차원·시간에 다른 수량이면 DuplicateRecord")
        void duplicateWithDifferentQuantity() {
            post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "analysis_run", 3, lastHour()))));
            Response dup = post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "analysis_run", 5, lastHour().plusSeconds(60)))));

            assertThat(dup.body().at("/Results/0/Status").asString()).isEqualTo("DuplicateRecord");
            assertThat(dup.body().at("/Results/0/MeteringRecordId").isMissingNode()).isTrue();
        }

        @Test
        @DisplayName("해지된 라이선스는 CustomerNotSubscribed")
        void cancelledLicense() {
            Response res = post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(UNSUBSCRIBED, "555555555555", "analysis_run", 1, lastHour()))));
            assertThat(res.body().at("/Results/0/Status").asString()).isEqualTo("CustomerNotSubscribed");
        }

        @Test
        @DisplayName("가이드 p16처럼 ProductCode를 함께 보내도 처리한다")
        void acceptsGuideShapeWithProductCode() {
            Response res = post("/mock-aws/metering/batch-meter-usage", Map.of(
                    "ProductCode", "prod-usage-001",
                    "UsageRecords", List.of(record(USAGE_ACTIVE, "111111111111", "data_gb", 25, lastHour()))));
            assertThat(res.body().at("/Results/0/Status").asString()).isEqualTo("Success");
        }

        @Test
        @DisplayName("요청 단위 오류: 26건, 24시간 초과, 없는 차원, 다른 ProductCode, 없는 라이선스")
        void rejectsInvalidBatches() {
            List<Map<String, Object>> tooMany = new ArrayList<>();
            for (int i = 0; i < 26; i++) {
                tooMany.add(record(USAGE_ACTIVE, "111111111111", "analysis_run", 1, lastHour()));
            }
            assertThat(post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords", tooMany)).text("/__type"))
                    .isEqualTo("ValidationException");

            Instant old = clock.now().minus(Duration.ofHours(24));
            assertThat(post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "analysis_run", 1, old)))).text("/__type"))
                    .isEqualTo("TimestampOutOfBoundsException");

            assertThat(post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "seats", 1, lastHour())))).text("/__type"))
                    .isEqualTo("InvalidUsageDimensionException");

            assertThat(post("/mock-aws/metering/batch-meter-usage", Map.of("ProductCode", "prod-hybrid-001",
                    "UsageRecords", List.of(record(USAGE_ACTIVE, "111111111111", "analysis_run", 1, lastHour()))))
                    .text("/__type")).isEqualTo("InvalidProductCodeException");

            assertThat(post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record("arn:aws:license-manager::1:license:l-x", "111111111111", "analysis_run", 1, lastHour()))))
                    .text("/__type")).isEqualTo("InvalidLicenseException");
        }

        @Test
        @DisplayName("요청 하나에는 한 제품의 레코드만 담을 수 있다 (AWS: 요청당 제품 1개)")
        void rejectsMixedProducts() {
            String hybrid = "arn:aws:license-manager::333333333333:license:l-hybrid-overage";
            Response res = post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords", List.of(
                    record(USAGE_ACTIVE, "111111111111", "analysis_run", 1, lastHour()),
                    record(hybrid, "333333333333", "analysis_run", 1, lastHour()))));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.text("/__type")).isEqualTo("ValidationException");
        }

        @Test
        @DisplayName("장애 주입(UNPROCESSED 1건) 시 해당 레코드는 UnprocessedRecords로 돌아온다")
        void unprocessedRecords() {
            put("/mock-aws/_sim/faults", Map.of("api", "BATCH_METER_USAGE", "mode", "UNPROCESSED", "remaining", 1));
            Response res = post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords", List.of(
                    record(USAGE_ACTIVE, "111111111111", "analysis_run", 1, lastHour()),
                    record(USAGE_ACTIVE, "111111111111", "data_gb", 2, lastHour()))));

            assertThat(res.body().get("UnprocessedRecords")).hasSize(1);
            assertThat(res.body().get("Results")).hasSize(1);
            assertThat(res.body().at("/UnprocessedRecords/0/Dimension").asString()).isEqualTo("analysis_run");
        }

        @Test
        @DisplayName("호출 로그에 요청/응답/오류 유형이 남는다")
        void recordsCallLog() {
            post("/mock-aws/metering/batch-meter-usage", Map.of("UsageRecords",
                    List.of(record(USAGE_ACTIVE, "111111111111", "seats", 1, lastHour()))));
            Response calls = get("/mock-aws/_sim/calls?licenseArn=" + USAGE_ACTIVE);
            assertThat(calls.body()).isNotEmpty();
            JsonNode latest = calls.body().get(0);
            assertThat(latest.get("api").asString()).isEqualTo("BatchMeterUsage");
            assertThat(latest.get("httpStatus").asInt()).isEqualTo(400);
            assertThat(latest.get("errorType").asString()).isEqualTo("InvalidUsageDimensionException");
        }
    }
}
