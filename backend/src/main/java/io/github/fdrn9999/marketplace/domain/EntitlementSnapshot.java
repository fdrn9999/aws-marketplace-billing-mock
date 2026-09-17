package io.github.fdrn9999.marketplace.domain;

import java.time.Instant;

/**
 * GetEntitlements 응답 항목(가이드 p17)의 로컬 사본.
 * 계약 요금제는 {@code dimension}, 계약 수량은 {@code Value.IntegerValue}, 만료 일시는 {@code ExpirationDate}.
 */
public record EntitlementSnapshot(String dimension, Integer quantity, Instant expirationDate) {

    public boolean isActiveAt(Instant now) {
        return expirationDate == null || expirationDate.isAfter(now);
    }
}
