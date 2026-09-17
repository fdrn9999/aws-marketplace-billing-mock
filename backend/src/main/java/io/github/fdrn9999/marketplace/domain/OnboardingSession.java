package io.github.fdrn9999.marketplace.domain;

import java.time.Instant;

/**
 * Fulfillment URL에서 등록 토큰을 즉시 조회(redeem)한 결과.
 * 토큰 자체는 브라우저 URL에 노출하지 않고, 등록 화면은 이 세션 ID로 진행한다.
 */
public record OnboardingSession(
        String id,
        String licenseArn,
        String customerAWSAccountId,
        String customerIdentifier,
        String productCode,
        Instant createdAt,
        Instant expiresAt,
        boolean completed) {

    public boolean isUsableAt(Instant now) {
        return !completed && now.isBefore(expiresAt);
    }

    public OnboardingSession markCompleted() {
        return new OnboardingSession(id, licenseArn, customerAWSAccountId, customerIdentifier, productCode,
                createdAt, expiresAt, true);
    }
}
