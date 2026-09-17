package io.github.fdrn9999.marketplace.usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.fdrn9999.marketplace.access.EntitlementGuard;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.UsageEvent;
import io.github.fdrn9999.marketplace.metering.MeteringBuckets;
import io.github.fdrn9999.marketplace.store.IdempotencyRepository;
import io.github.fdrn9999.marketplace.store.UsageEventRepository;
import io.github.fdrn9999.marketplace.subscription.SubscriptionContext;

/**
 * 과제 흐름의 "③ 사용량 발생" 단계.
 * 보호 기능(AI 분석 실행) 1회 = analysis_run 1 (+ 상품에 data_gb 차원이 있으면 처리 데이터량 N GB).
 */
@Service
public class UsageService {

    static final String ANALYSIS_RUN = "analysis_run";
    static final String DATA_GB = "data_gb";

    public record UsageLine(String dimension, long quantity, long includedQuantity, long meteredQuantity,
            Long includedRemaining) {
    }

    public record RunResult(String runId, String subscriberId, List<UsageLine> usage, boolean overage,
            boolean replayed, Instant recordedAt) {

        RunResult asReplay() {
            return new RunResult(runId, subscriberId, usage, overage, true, recordedAt);
        }
    }

    private final EntitlementGuard guard;
    private final UsageEventRepository usageEvents;
    private final MeteringBuckets buckets;
    private final IdempotencyRepository idempotency;
    private final KeyedLocks locks;
    private final SimulatedClock clock;

    public UsageService(EntitlementGuard guard, UsageEventRepository usageEvents, MeteringBuckets buckets,
            IdempotencyRepository idempotency, KeyedLocks locks, SimulatedClock clock) {
        this.guard = guard;
        this.usageEvents = usageEvents;
        this.buckets = buckets;
        this.idempotency = idempotency;
        this.locks = locks;
        this.clock = clock;
    }

    public RunResult runAnalysis(String customerId, int dataGb, String idempotencyKey) {
        if (dataGb < 1 || dataGb > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "dataGb는 1~100 사이여야 합니다", Map.of("dataGb", dataGb));
        }
        SubscriptionContext context = guard.requireActive(customerId);
        Subscriber subscriber = context.subscriber();
        Product product = context.product();
        String fingerprint = "analysis|dataGb=" + dataGb;

        return locks.withLock(subscriber.getLicenseArn(), () -> {
            if (StringUtils.hasText(idempotencyKey)) {
                IdempotencyRepository.Entry previous = idempotency.find(subscriber.getSubscriberId(), idempotencyKey).orElse(null);
                if (previous != null) {
                    if (!previous.fingerprint().equals(fingerprint)) {
                        throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    }
                    return ((RunResult) previous.response()).asReplay();
                }
            }

            Instant now = clock.now();
            Map<String, Long> requested = new LinkedHashMap<>();
            requested.put(ANALYSIS_RUN, 1L);
            if (product.dimension(DATA_GB).isPresent()) {
                requested.put(DATA_GB, (long) dataGb);
            }

            // 1) 모든 차원을 먼저 검사한다. 하나라도 실패하면 아무것도 기록하지 않는다
            List<UsageLine> lines = new ArrayList<>();
            for (Map.Entry<String, Long> entry : requested.entrySet()) {
                String dimension = entry.getKey();
                EntitlementSnapshot entitlement = null;
                long usedInTerm = 0;
                if (product.pricingModel().usesEntitlements()) {
                    entitlement = activeEntitlement(subscriber, dimension, now);
                    usedInTerm = usageEvents.sumIncluded(subscriber.getSubscriberId(), dimension, subscriber.getTermStartAt());
                }
                UsagePlanner.Split split = UsagePlanner.split(product.pricingModel(), dimension, entry.getValue(),
                        entitlement, usedInTerm);
                Long remaining = entitlement == null || entitlement.quantity() == null ? null
                        : Math.max(0, entitlement.quantity() - usedInTerm - split.includedQuantity());
                lines.add(new UsageLine(dimension, entry.getValue(), split.includedQuantity(), split.meteredQuantity(),
                        remaining));
            }

            // 2) 기록: 사용량 이벤트 + 미터링 버킷 누적
            String runId = Ids.next("run");
            for (UsageLine line : lines) {
                usageEvents.save(new UsageEvent(Ids.next("evt"), subscriber.getSubscriberId(),
                        subscriber.getLicenseArn(), line.dimension(), line.quantity(), line.includedQuantity(),
                        line.meteredQuantity(), idempotencyKey, now));
                if (product.pricingModel().usesMetering() && line.meteredQuantity() > 0) {
                    buckets.add(subscriber, line.dimension(), line.meteredQuantity(), now);
                }
            }
            boolean overage = product.pricingModel().usesEntitlements()
                    && lines.stream().anyMatch(l -> l.meteredQuantity() > 0);
            RunResult result = new RunResult(runId, subscriber.getSubscriberId(), lines, overage, false, now);
            if (StringUtils.hasText(idempotencyKey)) {
                idempotency.save(subscriber.getSubscriberId(), idempotencyKey,
                        new IdempotencyRepository.Entry(fingerprint, result));
            }
            return result;
        });
    }

    /** 차원 단위 권한 확인: 이 차원의 유효한 계약이 없으면 403 (일부 차원만 만료된 경우) */
    private static EntitlementSnapshot activeEntitlement(Subscriber subscriber, String dimension, Instant now) {
        return subscriber.getEntitlements().stream()
                .filter(e -> dimension.equals(e.dimension()) && e.isActiveAt(now))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.SUBSCRIPTION_EXPIRED,
                        "'" + dimension + "' 차원의 유효한 계약이 없습니다",
                        Map.of("reason", StatusReason.CONTRACT_EXPIRED.name(), "dimension", dimension)));
    }
}
