package io.github.fdrn9999.marketplace.metering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.support.HttpTestSupport;
import tools.jackson.databind.JsonNode;

/** 사용량 발생 → 시간 경과 → 미터링 실행 → Mock AWS 반영 → 청구 요약까지 (과제 흐름 ③④⑤). */
class MeteringFlowTest extends HttpTestSupport {

    long countStatus(JsonNode records, String status) {
        long n = 0;
        for (JsonNode r : records) {
            if (status.equals(r.get("status").asString())) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("시드 상태에서 미터링 실행: 마감된 PENDING과 0 레코드만 전송, 현재 시간대는 남는다")
    void runsOnSeedData() {
        Response run = post("/api/admin/metering/run", null);
        assertThat(run.status()).isEqualTo(200);
        // 사용량형: -1h 2건 + 보충된 0 레코드(-2h) 1건, 혼합형: -2h 1건
        assertThat(run.body().get("zeroRecordsCreated").asInt()).isEqualTo(1);
        assertThat(run.body().get("success").asInt()).isEqualTo(4);
        assertThat(run.body().get("failed").asInt()).isZero();

        JsonNode records = get("/api/me/billing", customer("sub-usage-active")).body().get("records");
        assertThat(countStatus(records, "PENDING")).as("현재 시간대 1건").isEqualTo(1);
        assertThat(countStatus(records, "SUCCESS")).isEqualTo(5);

        Response again = post("/api/admin/metering/run", null);
        assertThat(again.body().get("claimed").asInt()).isZero();
    }

    @Test
    @DisplayName("사용 → +1시간 → 미터링 → AWS 수락 수량과 예상 금액이 청구 요약에 반영된다")
    void usageFlowsIntoBilling() {
        post("/api/admin/metering/run", null);
        post("/api/features/analysis/run", Map.of("dataGb", 5), customer("sub-usage-active"));
        post("/api/features/analysis/run", Map.of("dataGb", 5), customer("sub-usage-active"));

        Response before = get("/api/me/billing", customer("sub-usage-active"));
        long pendingBefore = before.body().at("/charges/0/pendingQuantity").asLong();
        assertThat(pendingBefore).as("시드 1 + 방금 2").isEqualTo(3);

        Response clockRes = post("/api/admin/clock", Map.of("advance", "1h"));
        assertThat(clockRes.body().get("offsetSeconds").asLong()).isEqualTo(3600);
        Response run = post("/api/admin/metering/run", null);
        // 마감된 시간대: 사용량형 analysis_run·data_gb 버킷 + 혼합형 시드 버킷(현재 시간대였던 1건)
        assertThat(run.body().get("success").asInt()).isEqualTo(3);

        Response after = get("/api/me/billing", customer("sub-usage-active"));
        JsonNode analysis = after.body().at("/charges/0");
        assertThat(analysis.get("dimension").asString()).isEqualTo("analysis_run");
        assertThat(analysis.get("pendingQuantity").asLong()).isZero();
        long reported = analysis.get("reportedQuantity").asLong();
        assertThat(analysis.get("amountUsd").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("0.50").multiply(java.math.BigDecimal.valueOf(reported)));
        JsonNode latest = after.body().at("/records/0");
        assertThat(latest.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(latest.get("meteringRecordId").asString()).startsWith("mrec-");

        Response usage = get("/api/me/usage", customer("sub-usage-active"));
        assertThat(usage.text("/pricingModel")).isEqualTo("SUBSCRIPTION");
        assertThat(usage.body().get("hourly")).hasSize(12);
    }

    @Test
    @DisplayName("혼합형 사용량 요약: 포함량 50 모두 사용, 초과분은 미터링 대상")
    void hybridUsageSummary() {
        Response usage = get("/api/me/usage", customer("sub-hybrid-overage"));
        JsonNode d = usage.body().at("/dimensions/0");
        assertThat(d.get("included").asLong()).isEqualTo(50);
        assertThat(d.get("includedUsed").asLong()).isEqualTo(50);
        assertThat(d.get("includedRemaining").asLong()).isZero();
        assertThat(d.get("metered").asLong()).isEqualTo(8);
        assertThat(usage.text("/period/label")).isEqualTo("현재 계약 기간");
    }

    @Test
    @DisplayName("해지 후 남은 사용량을 보내면 CustomerNotSubscribed로 FAILED 처리된다")
    void cancelledSubscriptionRecordsFail() {
        post("/mock-aws/_sim/events", Map.of("type", "SUBSCRIPTION_CANCELLED",
                "licenseArn", "arn:aws:license-manager::111111111111:license:l-usage-active"));
        Response run = post("/api/admin/metering/run", null);
        assertThat(run.body().get("customerNotSubscribed").asInt()).isEqualTo(2);
        assertThat(get("/api/me/subscription", customer("sub-usage-active")).text("/reason"))
                .as("이미 해지 사유가 있으면 유지").isEqualTo("UNSUBSCRIBED");
    }

    @Test
    @DisplayName("관리 API: 고객 목록, 시계 검증, 초기화")
    void adminEndpoints() {
        Response customers = get("/api/customers");
        assertThat(customers.body()).hasSize(7);
        assertThat(customers.body().get(6).get("customerId").asString()).isEqualTo("guest");

        assertThat(post("/api/admin/clock", Map.of("advance", "-1h")).text("/error/code"))
                .isEqualTo("INVALID_CLOCK_OPERATION");
        assertThat(post("/api/admin/clock", Map.of("advance", "401d")).status()).isEqualTo(400);
        assertThat(post("/api/admin/clock", Map.of("advance", "PT90M")).body().get("offsetSeconds").asLong())
                .isEqualTo(5400);

        Response reset = post("/api/admin/reset", null);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(get("/api/admin/clock").body().get("offsetSeconds").asLong()).isZero();
    }

    @Test
    @DisplayName("BatchMeterUsage 장애가 재시도 한도를 넘으면 PENDING으로 남고, 장애 해소 후 전송된다")
    void meteringOutageThenRecovery() {
        put("/mock-aws/_sim/faults", Map.of("api", "BATCH_METER_USAGE", "mode", "INTERNAL_ERROR", "remaining", -1));
        Response failed = post("/api/admin/metering/run", null);
        assertThat(failed.body().get("success").asInt()).isZero();
        assertThat(failed.body().get("retryLater").asInt()).isEqualTo(4);
        assertThat(failed.body().get("errors").get(0).asString()).startsWith("InternalServiceErrorException");

        put("/mock-aws/_sim/faults", Map.of("api", "BATCH_METER_USAGE", "mode", "NONE"));
        assertThat(post("/api/admin/metering/run", null).body().get("success").asInt()).isEqualTo(4);
    }
}
