package io.github.fdrn9999.marketplace.domain;

/**
 * 가이드(p8, p15)의 SaaS 과금 모델과 각 모델이 사용하는 AWS API.
 */
public enum PricingModel {
    /** 사용량 기반(pay as you go). 1시간마다 BatchMeterUsage, 사용량이 없어도 0 전송. */
    SUBSCRIPTION(false, true),
    /** 계약 기반(선결제). GetEntitlements로 권한 확인, 만료일 확인이 중요. */
    CONTRACT(true, false),
    /** 혼합형. 기본요금은 GetEntitlements, 계약 수량 초과분은 BatchMeterUsage. */
    CONTRACT_WITH_SUBSCRIPTION(true, true);

    private final boolean usesEntitlements;
    private final boolean usesMetering;

    PricingModel(boolean usesEntitlements, boolean usesMetering) {
        this.usesEntitlements = usesEntitlements;
        this.usesMetering = usesMetering;
    }

    /** GetEntitlements 기반 권한 확인이 필요한 모델인지 */
    public boolean usesEntitlements() {
        return usesEntitlements;
    }

    /** BatchMeterUsage로 사용량을 보고하는 모델인지 */
    public boolean usesMetering() {
        return usesMetering;
    }
}
