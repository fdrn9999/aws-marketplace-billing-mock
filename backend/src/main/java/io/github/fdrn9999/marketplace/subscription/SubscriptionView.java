package io.github.fdrn9999.marketplace.subscription;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.SubscriptionStatus;

/** GET /api/me/subscription 응답. */
public record SubscriptionView(
        String customerId,
        boolean registered,
        String companyName,
        String customerAWSAccountId,
        String licenseArn,
        ProductView product,
        SubscriptionStatus status,
        StatusReason reason,
        boolean canUseFeatures,
        boolean freeTrial,
        List<EntitlementView> entitlements,
        Instant expiresAt,
        Long daysUntilExpiry,
        boolean expiringSoon,
        Instant lastEntitlementSyncAt,
        boolean stale,
        String syncError,
        Instant serverTime) {

    /** 만료 임박 경고 기준 */
    static final Duration EXPIRING_SOON = Duration.ofDays(7);

    public record ProductView(String productCode, String name, String description, PricingModel pricingModel,
            BigDecimal contractPriceUsd, List<Product.Dimension> dimensions) {

        static ProductView of(Product p) {
            return new ProductView(p.productCode(), p.name(), p.description(), p.pricingModel(),
                    p.contractPriceUsd(), p.dimensions());
        }
    }

    public record EntitlementView(String dimension, Integer quantity, Instant expirationDate, boolean active) {
    }

    public static SubscriptionView of(SubscriptionContext context, Instant now) {
        Subscriber s = context.subscriber();
        StatusEvaluation evaluation = context.evaluation();
        if (s == null) {
            return new SubscriptionView(context.customerId(), false, null, null, null, null, evaluation.status(),
                    evaluation.reason(), false, false, List.of(), null, null, false, null, false, null, now);
        }
        List<EntitlementView> entitlements = s.getEntitlements().stream()
                .map((EntitlementSnapshot e) -> new EntitlementView(e.dimension(), e.quantity(), e.expirationDate(),
                        e.isActiveAt(now)))
                .toList();
        Instant expiresAt = evaluation.expiresAt();
        Long daysLeft = expiresAt == null ? null : Math.max(0, Duration.between(now, expiresAt).toDays());
        boolean expiringSoon = expiresAt != null && Duration.between(now, expiresAt).compareTo(EXPIRING_SOON) <= 0;
        return new SubscriptionView(context.customerId(), true, s.getCompanyName(), s.getCustomerAWSAccountId(),
                s.getLicenseArn(), ProductView.of(context.product()), evaluation.status(), evaluation.reason(),
                evaluation.isActive(), s.isFreeTrialTermPresent(), entitlements, expiresAt, daysLeft, expiringSoon,
                s.getLastEntitlementSyncAt(), context.freshness().stale(), context.freshness().error(), now);
    }
}
