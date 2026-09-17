package io.github.fdrn9999.marketplace.domain;

import java.time.Instant;

/**
 * SaaS 애플리케이션이 기록한 사용량 한 건.
 *
 * @param includedQuantity 계약 수량에서 차감된 양 (CONTRACT / CONTRACT_WITH_SUBSCRIPTION)
 * @param meteredQuantity  BatchMeterUsage로 보고할 양 (SUBSCRIPTION은 전량, 혼합형은 초과분, CONTRACT는 0)
 */
public record UsageEvent(
        String id,
        String subscriberId,
        String licenseArn,
        String dimension,
        long quantity,
        long includedQuantity,
        long meteredQuantity,
        String idempotencyKey,
        Instant occurredAt) {
}
