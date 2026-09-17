package io.github.fdrn9999.marketplace.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.UsageEvent;
import io.github.fdrn9999.marketplace.store.MeteringRecordRepository;
import io.github.fdrn9999.marketplace.store.UsageEventRepository;
import io.github.fdrn9999.marketplace.subscription.SubscriptionContext;
import io.github.fdrn9999.marketplace.subscription.SubscriptionService;

/**
 * 과제 흐름의 "⑤ UI 반영"에 필요한 사용량/청구 요약.
 * 청구 금액은 화면 표시용 추정치이며, 실제 청구는 AWS Marketplace가 수행한다.
 */
@Service
public class BillingService {

    static final int RECENT_HOURS = 12;
    static final int RECENT_RECORDS = 50;

    /**
     * 집계 기간: 사용량형은 현재 달(UTC), 계약형/혼합형은 현재 계약 기간(termStartAt ~ 만료일).
     */
    public record Period(Instant start, Instant end, String label) {
    }

    /**
     * @param included          계약 수량 (계약형/혼합형)
     * @param includedUsed      계약 수량에서 차감된 양
     * @param includedRemaining 남은 계약 수량
     * @param metered           미터링 대상으로 기록된 양 (사용량형 전량 / 혼합형 초과분)
     */
    public record DimensionUsage(String key, String name, String unit, long used, Long included, long includedUsed,
            Long includedRemaining, long metered, BigDecimal unitPriceUsd) {
    }

    public record HourlyPoint(Instant hourStart, Map<String, Long> quantities) {
    }

    public record UsageSummary(String customerId, boolean registered, PricingModel pricingModel, Period period,
            List<DimensionUsage> dimensions, List<HourlyPoint> hourly, Instant serverTime) {
    }

    public record RecordView(String id, String dimension, Instant hourStart, long quantity, MeteringStatus status,
            String meteringRecordId, int attempts, String lastError, Instant sentAt) {

        static RecordView of(MeteringRecord r) {
            return new RecordView(r.getId(), r.getDimension(), r.getHourStart(), r.getQuantity(), r.getStatus(),
                    r.getMeteringRecordId(), r.getAttempts(), r.getLastError(), r.getSentAt());
        }
    }

    /**
     * @param reportedQuantity AWS가 수락한 수량 (SUCCESS)
     * @param pendingQuantity  아직 보고되지 않은 수량 (PENDING/SENDING)
     * @param amountUsd        reportedQuantity × 단가
     */
    public record ChargeLine(String dimension, String name, long reportedQuantity, long pendingQuantity,
            BigDecimal unitPriceUsd, BigDecimal amountUsd) {
    }

    public record BillingSummary(String customerId, boolean registered, String month, BigDecimal contractPriceUsd,
            List<ChargeLine> charges, BigDecimal meteredAmountUsd, Map<MeteringStatus, Long> recordCounts,
            List<RecordView> records, String note, Instant serverTime) {
    }

    private final SubscriptionService subscriptions;
    private final UsageEventRepository usageEvents;
    private final MeteringRecordRepository meteringRecords;
    private final SimulatedClock clock;

    public BillingService(SubscriptionService subscriptions, UsageEventRepository usageEvents,
            MeteringRecordRepository meteringRecords, SimulatedClock clock) {
        this.subscriptions = subscriptions;
        this.usageEvents = usageEvents;
        this.meteringRecords = meteringRecords;
        this.clock = clock;
    }

    public UsageSummary usage(String customerId) {
        Instant now = clock.now();
        SubscriptionContext context = subscriptions.load(customerId);
        if (!context.isRegistered()) {
            return new UsageSummary(customerId, false, null, null, List.of(), List.of(), now);
        }
        Subscriber subscriber = context.subscriber();
        Product product = context.product();
        Period period = period(subscriber, product, context, now);
        List<UsageEvent> events = usageEvents.findBySubscriberSince(subscriber.getSubscriberId(), period.start());

        List<DimensionUsage> dimensions = new ArrayList<>();
        for (Product.Dimension d : product.dimensions()) {
            long used = 0;
            long includedUsed = 0;
            long metered = 0;
            for (UsageEvent e : events) {
                if (e.dimension().equals(d.key())) {
                    used += e.quantity();
                    includedUsed += e.includedQuantity();
                    metered += e.meteredQuantity();
                }
            }
            Long included = null;
            Long remaining = null;
            if (product.pricingModel().usesEntitlements()) {
                included = subscriber.getEntitlements().stream()
                        .filter(e -> d.key().equals(e.dimension()) && e.isActiveAt(now) && e.quantity() != null)
                        .map(EntitlementSnapshot::quantity)
                        .findFirst()
                        .map(Integer::longValue)
                        .orElse(0L);
                remaining = Math.max(0, included - includedUsed);
            }
            dimensions.add(new DimensionUsage(d.key(), d.name(), d.unit(), used, included, includedUsed, remaining,
                    metered, d.meteringUnitPriceUsd()));
        }
        return new UsageSummary(customerId, true, product.pricingModel(), period, dimensions,
                hourly(subscriber, product, now), now);
    }

    public BillingSummary billing(String customerId) {
        Instant now = clock.now();
        SubscriptionContext context = subscriptions.load(customerId);
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        String note = "예상 금액은 AWS가 수락한(SUCCESS) 사용량 × 단가로 계산한 추정치입니다. 실제 청구는 AWS Marketplace가 수행합니다.";
        if (!context.isRegistered()) {
            return new BillingSummary(customerId, false, month.toString(), null, List.of(), BigDecimal.ZERO,
                    Map.of(), List.of(), note, now);
        }
        Product product = context.product();
        List<MeteringRecord> all = meteringRecords.findBySubscriber(context.subscriber().getSubscriberId());
        Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<ChargeLine> charges = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        if (product.pricingModel().usesMetering()) {
            for (Product.Dimension d : product.dimensions()) {
                long reported = 0;
                long pending = 0;
                for (MeteringRecord r : all) {
                    if (!r.getDimension().equals(d.key()) || r.getHourStart().isBefore(monthStart)) {
                        continue;
                    }
                    if (r.getStatus() == MeteringStatus.SUCCESS) {
                        reported += r.getQuantity();
                    } else if (r.getStatus() == MeteringStatus.PENDING || r.getStatus() == MeteringStatus.SENDING) {
                        pending += r.getQuantity();
                    }
                }
                BigDecimal price = d.meteringUnitPriceUsd() == null ? BigDecimal.ZERO : d.meteringUnitPriceUsd();
                BigDecimal amount = price.multiply(BigDecimal.valueOf(reported)).setScale(2, RoundingMode.HALF_UP);
                total = total.add(amount);
                charges.add(new ChargeLine(d.key(), d.name(), reported, pending, d.meteringUnitPriceUsd(), amount));
            }
        }

        Map<MeteringStatus, Long> counts = new EnumMap<>(MeteringStatus.class);
        for (MeteringStatus status : MeteringStatus.values()) {
            counts.put(status, all.stream().filter(r -> r.getStatus() == status).count());
        }
        List<RecordView> recent = all.stream().limit(RECENT_RECORDS).map(RecordView::of).toList();
        return new BillingSummary(customerId, true, month.toString(), product.contractPriceUsd(), charges,
                total.setScale(2, RoundingMode.HALF_UP), counts, recent, note, now);
    }

    private Period period(Subscriber subscriber, Product product, SubscriptionContext context, Instant now) {
        if (product.pricingModel().usesEntitlements()) {
            Instant start = subscriber.getTermStartAt() == null ? subscriber.getCreatedAt() : subscriber.getTermStartAt();
            Instant end = context.evaluation().expiresAt();
            if (end == null) {
                end = subscriber.getEntitlements().stream()
                        .map(EntitlementSnapshot::expirationDate)
                        .filter(java.util.Objects::nonNull)
                        .max(Instant::compareTo)
                        .orElse(null);
            }
            return new Period(start, end, "현재 계약 기간");
        }
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        return new Period(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), month + " (UTC)");
    }

    /** 최근 12시간의 시간대별 사용량 (차트용) */
    private List<HourlyPoint> hourly(Subscriber subscriber, Product product, Instant now) {
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        Instant from = currentHour.minus(RECENT_HOURS - 1L, ChronoUnit.HOURS);
        Map<Instant, Map<String, Long>> buckets = new LinkedHashMap<>();
        for (Instant h = from; !h.isAfter(currentHour); h = h.plus(1, ChronoUnit.HOURS)) {
            Map<String, Long> zero = new LinkedHashMap<>();
            product.dimensions().forEach(d -> zero.put(d.key(), 0L));
            buckets.put(h, zero);
        }
        for (UsageEvent e : usageEvents.findBySubscriberSince(subscriber.getSubscriberId(), from)) {
            Map<String, Long> bucket = buckets.get(e.occurredAt().truncatedTo(ChronoUnit.HOURS));
            if (bucket != null) {
                bucket.merge(e.dimension(), e.quantity(), Long::sum);
            }
        }
        return buckets.entrySet().stream().map(en -> new HourlyPoint(en.getKey(), en.getValue())).toList();
    }
}
