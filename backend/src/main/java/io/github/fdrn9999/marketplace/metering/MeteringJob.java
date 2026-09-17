package io.github.fdrn9999.marketplace.metering;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.awsapi.MeteringApi;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecord;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecordResult;
import io.github.fdrn9999.marketplace.client.MarketplaceApiException;
import io.github.fdrn9999.marketplace.client.MarketplaceClient;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.MeteringRecordRepository;
import io.github.fdrn9999.marketplace.store.ProductRepository;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;

/**
 * 과제 흐름의 "④ Billing/Metering 처리" 단계 (QuickStart: EventBridge Hourly → MeteringSQSHandler → BatchMeterUsage).
 *
 * <ol>
 *   <li>사용량 0 보충: ACTIVE인 사용량형 구독의 마감된 시간대에 레코드가 없으면 quantity 0 레코드 생성 (가이드 p15)</li>
 *   <li>대상 선정: 마감된 시간대(hourStart + 1h ≤ now)의 PENDING 레코드. 현재 시간대는 계속 누적되므로 보내지 않는다</li>
 *   <li>사전 차단: 24시간 한도에 걸리는 레코드는 보내지 않고 FAILED (요청 단위 예외로 배치 전체가 실패하는 것을 방지)</li>
 *   <li>전송: 상품별로 묶어 25건씩 BatchMeterUsage 호출 (LicenseArn 기반, 요청 ProductCode 생략)</li>
 *   <li>결과 반영: Success / DuplicateRecord / CustomerNotSubscribed / UnprocessedRecords</li>
 * </ol>
 * 동시에 두 번 실행되지 않도록 tryLock으로 막는다(실행 중이면 409).
 */
@Service
public class MeteringJob {

    private static final Logger log = LoggerFactory.getLogger(MeteringJob.class);
    /** Mock AWS가 요청을 받는 시점과의 차이를 감안한 24시간 한도 여유 */
    static final Duration AGE_SAFETY_MARGIN = Duration.ofMinutes(5);

    public record RunSummary(
            Instant startedAt,
            int zeroRecordsCreated,
            int claimed,
            int batches,
            int success,
            int duplicate,
            int customerNotSubscribed,
            int unprocessed,
            int retryLater,
            int failed,
            List<String> errors) {
    }

    /** 한 번의 실행에서 집계를 모으는 가변 객체 */
    private static final class Tally {
        int zero;
        int claimed;
        int batches;
        int success;
        int duplicate;
        int notSubscribed;
        int unprocessed;
        int retryLater;
        int failed;
        final List<String> errors = new ArrayList<>();
    }

    private final ReentrantLock runLock = new ReentrantLock();
    private final MarketplaceClient client;
    private final MeteringRecordRepository records;
    private final SubscriberRepository subscribers;
    private final ProductRepository products;
    private final KeyedLocks locks;
    private final SimulatedClock clock;
    private final AppProperties.Metering settings;

    public MeteringJob(MarketplaceClient client, MeteringRecordRepository records, SubscriberRepository subscribers,
            ProductRepository products, KeyedLocks locks, SimulatedClock clock, AppProperties properties) {
        this.client = client;
        this.records = records;
        this.subscribers = subscribers;
        this.products = products;
        this.locks = locks;
        this.clock = clock;
        this.settings = properties.metering();
    }

    public RunSummary run() {
        if (!runLock.tryLock()) {
            throw new ApiException(ErrorCode.METERING_ALREADY_RUNNING);
        }
        try {
            return doRun();
        } finally {
            runLock.unlock();
        }
    }

    private RunSummary doRun() {
        Instant startedAt = clock.now();
        Tally tally = new Tally();
        createZeroUsageRecords(tally);
        List<MeteringRecord> claimed = claimClosedRecords(tally);

        Map<String, List<MeteringRecord>> byProduct = new LinkedHashMap<>();
        for (MeteringRecord record : claimed) {
            byProduct.computeIfAbsent(record.getProductCode(), k -> new ArrayList<>()).add(record);
        }
        int batchSize = Math.max(1, Math.min(settings.batchSize(), 25));
        for (List<MeteringRecord> productRecords : byProduct.values()) {
            for (int i = 0; i < productRecords.size(); i += batchSize) {
                send(productRecords.subList(i, Math.min(productRecords.size(), i + batchSize)), tally, true);
            }
        }
        RunSummary summary = new RunSummary(startedAt, tally.zero, tally.claimed, tally.batches, tally.success,
                tally.duplicate, tally.notSubscribed, tally.unprocessed, tally.retryLater, tally.failed,
                List.copyOf(tally.errors));
        log.info("미터링 실행 완료: {}", summary);
        return summary;
    }

    // ------------------------------------------------------------------ 1) 사용량 0 보충

    private void createZeroUsageRecords(Tally tally) {
        Instant currentHour = clock.currentHourStart();
        Instant windowStart = currentHour.minus(settings.zeroUsageBackfillHours(), ChronoUnit.HOURS);
        for (Subscriber subscriber : subscribers.findAll()) {
            Product product = products.findByCode(subscriber.getProductCode()).orElse(null);
            if (product == null || product.pricingModel() != PricingModel.SUBSCRIPTION
                    || !subscriber.isSuccessfullySubscribed() || subscriber.isSubscriptionExpired()
                    || subscriber.getTermStartAt() == null) {
                continue;
            }
            Instant start = max(windowStart, subscriber.getTermStartAt().truncatedTo(ChronoUnit.HOURS));
            locks.withLock(subscriber.getLicenseArn(), () -> {
                for (Instant hour = start; hour.isBefore(currentHour); hour = hour.plus(1, ChronoUnit.HOURS)) {
                    if (!hasAnyRecord(subscriber, product, hour)) {
                        MeteringRecord zero = MeteringBuckets.newRecord(subscriber,
                                product.primaryDimension().key(), hour, clock.now());
                        records.save(zero);
                        tally.zero++;
                    }
                }
            });
        }
    }

    private boolean hasAnyRecord(Subscriber subscriber, Product product, Instant hour) {
        return product.dimensions().stream().anyMatch(d -> records
                .findByKey(MeteringRecord.key(subscriber.getLicenseArn(), d.key(), hour)).isPresent());
    }

    // ------------------------------------------------------------------ 2) 3) 대상 선정과 사전 차단

    private List<MeteringRecord> claimClosedRecords(Tally tally) {
        Instant currentHour = clock.currentHourStart();
        Duration maxAge = settings.maxRecordAge().minus(AGE_SAFETY_MARGIN);
        List<MeteringRecord> claimed = new ArrayList<>();
        for (MeteringRecord candidate : records.findByStatus(MeteringStatus.PENDING)) {
            if (!candidate.getHourStart().isBefore(currentHour)) {
                continue;
            }
            locks.withLock(candidate.getLicenseArn(), () -> {
                if (candidate.getStatus() != MeteringStatus.PENDING) {
                    return;
                }
                Instant now = clock.now();
                if (Duration.between(candidate.getHourStart(), now).compareTo(maxAge) >= 0) {
                    // 전송하지 않고 실패 처리하므로 sentAt은 비워 둔다
                    finish(candidate, MeteringStatus.FAILED, "TIMESTAMP_OUT_OF_BOUNDS: 24시간이 지나 AWS가 받지 않는 레코드", null);
                    tally.failed++;
                    return;
                }
                candidate.setStatus(MeteringStatus.SENDING);
                candidate.setAttempts(candidate.getAttempts() + 1);
                candidate.setUpdatedAt(now);
                claimed.add(candidate);
                tally.claimed++;
            });
        }
        return claimed;
    }

    // ------------------------------------------------------------------ 4) 5) 전송과 결과 반영

    /**
     * @param isolateOnRequestError 요청 단위 오류(재시도 불가)가 나면 레코드를 한 건씩 다시 보내 문제 레코드만 실패 처리할지
     */
    private void send(List<MeteringRecord> batch, Tally tally, boolean isolateOnRequestError) {
        List<UsageRecord> payload = batch.stream().map(MeteringJob::toUsageRecord).toList();
        tally.batches++;
        BatchMeterUsageResult result;
        try {
            result = client.batchMeterUsage(payload);
        } catch (MarketplaceApiException e) {
            tally.errors.add(e.awsErrorType() + " (" + batch.size() + "건)");
            if (!e.isRetryable() && isolateOnRequestError && batch.size() > 1) {
                for (MeteringRecord record : batch) {
                    send(List.of(record), tally, false);
                }
                return;
            }
            for (MeteringRecord record : batch) {
                if (e.isRetryable()) {
                    retryLater(record, e.awsErrorType(), tally);
                } else {
                    locks.withLock(record.getLicenseArn(), () ->
                            finish(record, MeteringStatus.FAILED, e.awsErrorType(), clock.now()));
                    tally.failed++;
                }
            }
            return;
        }

        Map<String, MeteringRecord> byKey = new HashMap<>();
        for (MeteringRecord record : batch) {
            byKey.put(resultKey(record.getLicenseArn(), record.getDimension(), record.getHourStart().getEpochSecond()), record);
        }
        for (UsageRecordResult r : nullSafe(result.results())) {
            MeteringRecord record = byKey.remove(resultKey(r.usageRecord()));
            if (record != null) {
                applyResult(record, r, tally);
            }
        }
        for (UsageRecord unprocessed : nullSafe(result.unprocessedRecords())) {
            MeteringRecord record = byKey.remove(resultKey(unprocessed));
            if (record != null) {
                tally.unprocessed++;
                retryLater(record, "UnprocessedRecords", tally);
            }
        }
        // 응답에 없는 레코드는 결과를 알 수 없으므로 다시 보낸다 (재전송은 멱등)
        for (MeteringRecord missing : byKey.values()) {
            retryLater(missing, "응답에 결과가 없음", tally);
        }
    }

    private void applyResult(MeteringRecord record, UsageRecordResult result, Tally tally) {
        locks.withLock(record.getLicenseArn(), () -> {
            Instant now = clock.now();
            switch (result.status()) {
                case MeteringApi.SUCCESS -> {
                    record.setMeteringRecordId(result.meteringRecordId());
                    finish(record, MeteringStatus.SUCCESS, null, now);
                    tally.success++;
                }
                case MeteringApi.DUPLICATE_RECORD -> {
                    finish(record, MeteringStatus.DUPLICATE, "같은 시간대에 다른 수량이 이미 보고되어 반영되지 않음", now);
                    tally.duplicate++;
                }
                case MeteringApi.CUSTOMER_NOT_SUBSCRIBED -> {
                    finish(record, MeteringStatus.FAILED, MeteringApi.CUSTOMER_NOT_SUBSCRIBED, now);
                    markNotSubscribed(record, now);
                    tally.notSubscribed++;
                }
                default -> retryLater(record, "알 수 없는 상태: " + result.status(), tally);
            }
        });
    }

    /** AWS가 이 고객을 구독자로 인정하지 않으므로 로컬 상태도 만료로 맞춘다. */
    private void markNotSubscribed(MeteringRecord record, Instant now) {
        subscribers.findByLicenseArn(record.getLicenseArn()).ifPresent(s -> {
            if (!s.isSubscriptionExpired()) {
                s.setSubscriptionExpired(true);
                s.setExpiredReason(StatusReason.METERING_REJECTED);
                s.setUpdatedAt(now);
                subscribers.save(s);
            }
        });
    }

    private void retryLater(MeteringRecord record, String reason, Tally tally) {
        locks.withLock(record.getLicenseArn(), () -> {
            Instant now = clock.now();
            if (record.getAttempts() >= settings.maxAttempts()) {
                finish(record, MeteringStatus.FAILED, "MAX_ATTEMPTS: " + reason, now);
                tally.failed++;
            } else {
                record.setStatus(MeteringStatus.PENDING);
                record.setLastError(reason);
                record.setUpdatedAt(now);
                tally.retryLater++;
            }
        });
    }

    /** @param sentAt AWS로 보낸 시각. 보내지 않고 끝낸 경우 null */
    private void finish(MeteringRecord record, MeteringStatus status, String error, Instant sentAt) {
        record.setStatus(status);
        record.setLastError(error);
        record.setSentAt(sentAt);
        record.setUpdatedAt(clock.now());
    }

    static UsageRecord toUsageRecord(MeteringRecord record) {
        return new UsageRecord(record.getHourStart().getEpochSecond(), null, record.getCustomerAWSAccountId(),
                record.getDimension(), Math.toIntExact(record.getQuantity()), record.getLicenseArn());
    }

    private static String resultKey(UsageRecord r) {
        return r == null ? "" : resultKey(r.licenseArn(), r.dimension(), r.timestamp() == null ? 0 : r.timestamp());
    }

    private static String resultKey(String licenseArn, String dimension, long timestamp) {
        return licenseArn + "|" + dimension + "|" + timestamp;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
