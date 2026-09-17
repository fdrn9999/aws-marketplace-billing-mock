package io.github.fdrn9999.marketplace.usage;

import java.util.Map;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.PricingModel;

/**
 * 사용량 1건을 "계약 수량에서 차감할 양"과 "미터링할 양"으로 나누는 규칙 (순수 함수).
 *
 * <pre>
 * SUBSCRIPTION               : 전량 미터링
 * CONTRACT                   : 계약 수량 이내만 허용, 초과하면 403 QUOTA_EXCEEDED (미터링 없음)
 * CONTRACT_WITH_SUBSCRIPTION : included = min(요청량, 남은 계약 수량), metered = 요청량 - included
 * </pre>
 *
 * [Mock 정책] 계약 수량(Value.IntegerValue)은 "계약 기간(termStartAt ~ ExpirationDate) 동안의 총 실행 가능 횟수"로 해석한다.
 */
public final class UsagePlanner {

    public record Split(long includedQuantity, long meteredQuantity) {
    }

    private UsagePlanner() {
    }

    /**
     * @param entitlement  이 차원의 유효한 Entitlement (계약형이 아니면 null)
     * @param usedInTerm   현재 계약 기간에 이미 차감된 양
     */
    public static Split split(PricingModel model, String dimension, long quantity, EntitlementSnapshot entitlement,
            long usedInTerm) {
        if (quantity < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "사용량은 0 이상이어야 합니다");
        }
        if (!model.usesEntitlements()) {
            return new Split(0, quantity);
        }
        long limit = entitlement == null || entitlement.quantity() == null ? 0 : entitlement.quantity();
        long remaining = Math.max(0, limit - usedInTerm);
        if (model == PricingModel.CONTRACT) {
            if (quantity > remaining) {
                throw new ApiException(ErrorCode.QUOTA_EXCEEDED, ErrorCode.QUOTA_EXCEEDED.defaultMessage(), Map.of(
                        "dimension", dimension, "limit", limit, "used", usedInTerm, "requested", quantity));
            }
            return new Split(quantity, 0);
        }
        long included = Math.min(quantity, remaining);
        return new Split(included, quantity - included);
    }
}
