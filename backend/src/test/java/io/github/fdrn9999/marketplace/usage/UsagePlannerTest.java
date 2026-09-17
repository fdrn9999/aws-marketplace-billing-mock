package io.github.fdrn9999.marketplace.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.PricingModel;

class UsagePlannerTest {

    static final EntitlementSnapshot HUNDRED = new EntitlementSnapshot("analysis_run", 100, Instant.MAX);

    @Test
    @DisplayName("사용량형은 전량 미터링")
    void subscriptionMetersEverything() {
        UsagePlanner.Split split = UsagePlanner.split(PricingModel.SUBSCRIPTION, "analysis_run", 7, null, 0);
        assertThat(split).isEqualTo(new UsagePlanner.Split(0, 7));
    }

    @ParameterizedTest(name = "사용 {0} + 요청 {1} → 허용={2}")
    @CsvSource({"98, 1, true", "99, 1, true", "100, 1, false", "95, 6, false", "0, 100, true"})
    @DisplayName("계약형 한도 경계")
    void contractQuotaBoundary(long used, long requested, boolean allowed) {
        if (allowed) {
            UsagePlanner.Split split = UsagePlanner.split(PricingModel.CONTRACT, "analysis_run", requested, HUNDRED, used);
            assertThat(split).isEqualTo(new UsagePlanner.Split(requested, 0));
        } else {
            assertThatThrownBy(() -> UsagePlanner.split(PricingModel.CONTRACT, "analysis_run", requested, HUNDRED, used))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException api = (ApiException) e;
                        assertThat(api.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
                        assertThat(api.details()).containsEntry("limit", 100L).containsEntry("used", used);
                    });
        }
    }

    @ParameterizedTest(name = "사용 {0} + 요청 {1} → 포함 {2}, 초과 {3}")
    @CsvSource({"40, 5, 5, 0", "48, 5, 2, 3", "50, 5, 0, 5", "70, 1, 0, 1"})
    @DisplayName("혼합형: 포함량 안쪽은 차감, 넘는 부분만 미터링")
    void hybridSplit(long used, long requested, long included, long metered) {
        EntitlementSnapshot fifty = new EntitlementSnapshot("analysis_run", 50, Instant.MAX);
        UsagePlanner.Split split = UsagePlanner.split(PricingModel.CONTRACT_WITH_SUBSCRIPTION, "analysis_run",
                requested, fifty, used);
        assertThat(split).isEqualTo(new UsagePlanner.Split(included, metered));
    }

    @Test
    @DisplayName("음수 사용량은 거부")
    void rejectsNegative() {
        assertThatThrownBy(() -> UsagePlanner.split(PricingModel.SUBSCRIPTION, "analysis_run", -1, null, 0))
                .isInstanceOf(ApiException.class);
    }
}
