package io.github.fdrn9999.marketplace.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.Entitlement;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
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

/** 미터링 작업 단위 테스트. AWS 응답은 가짜 클라이언트로 제어한다. */
class MeteringJobTest {

    static final Instant NOW = Instant.parse("2026-09-17T05:30:00Z");
    static final Instant CURRENT_HOUR = NOW.truncatedTo(ChronoUnit.HOURS);

    /** 호출된 배치를 기록하고, 테스트가 지정한 함수로 응답을 만든다 */
    static class FakeClient implements MarketplaceClient {
        final List<List<UsageRecord>> calls = new ArrayList<>();
        Function<List<UsageRecord>, BatchMeterUsageResult> responder = FakeClient::allSuccess;

        static BatchMeterUsageResult allSuccess(List<UsageRecord> records) {
            List<UsageRecordResult> results = new ArrayList<>();
            for (UsageRecord r : records) {
                results.add(new UsageRecordResult("mrec-" + r.dimension() + "-" + r.timestamp(), MeteringApi.SUCCESS, r));
            }
            return new BatchMeterUsageResult(results, List.of());
        }

        @Override
        public ResolveCustomerResult resolveCustomer(String registrationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entitlement> getEntitlements(String productCode, String licenseArn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized BatchMeterUsageResult batchMeterUsage(List<UsageRecord> usageRecords) {
            calls.add(List.copyOf(usageRecords));
            return responder.apply(usageRecords);
        }
    }

    FakeClient client;
    MeteringRecordRepository records;
    SubscriberRepository subscribers;
    ProductRepository products;
    SimulatedClock clock;
    MeteringJob job;
    Subscriber subscriber;

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        records = new MeteringRecordRepository();
        subscribers = new SubscriberRepository();
        products = new ProductRepository();
        clock = new SimulatedClock(Clock.fixed(NOW, ZoneOffset.UTC));
        AppProperties properties = new AppProperties(true, "", "secret",
                new AppProperties.MockAws("", Duration.ofHours(1)),
                new AppProperties.Entitlement(Duration.ofMinutes(15), Duration.ofHours(24)),
                new AppProperties.Metering(25, 5, 3, 3, Duration.ofHours(24), Duration.ZERO));
        job = new MeteringJob(client, records, subscribers, products, new KeyedLocks(), clock, properties);

        products.replaceAll(List.of(new Product("prod-usage", "사용량형", "", PricingModel.SUBSCRIPTION, null, List.of(
                new Product.Dimension("analysis_run", "분석", "회", new BigDecimal("0.50")),
                new Product.Dimension("data_gb", "데이터", "GB", new BigDecimal("0.10"))))));
        subscriber = new Subscriber();
        subscriber.setSubscriberId("sub-1");
        subscriber.setLicenseArn("arn:aws:license-manager::111111111111:license:l-1");
        subscriber.setCustomerAWSAccountId("111111111111");
        subscriber.setProductCode("prod-usage");
        subscriber.setSuccessfullyRegistered(true);
        subscriber.setSuccessfullySubscribed(true);
        // 사용량 0 보충이 테스트에 끼어들지 않도록 구독 시작을 현재 시간대로 둔다
        subscriber.setTermStartAt(CURRENT_HOUR);
        subscribers.save(subscriber);
    }

    MeteringRecord pending(String dimension, Instant hourStart, long quantity) {
        MeteringRecord r = MeteringBuckets.newRecord(subscriber, dimension, hourStart, NOW);
        r.setQuantity(quantity);
        return records.save(r);
    }

    @Test
    @DisplayName("마감된 시간대 30건은 25건 + 5건 두 번에 나눠 보내고, 현재 시간대는 보내지 않는다")
    void chunksAndSkipsCurrentHour() {
        for (int i = 1; i <= 15; i++) {
            pending("analysis_run", CURRENT_HOUR.minus(i, ChronoUnit.HOURS), i);
            pending("data_gb", CURRENT_HOUR.minus(i, ChronoUnit.HOURS), i * 10L);
        }
        MeteringRecord current = pending("analysis_run", CURRENT_HOUR, 9);

        MeteringJob.RunSummary summary = job.run();

        assertThat(client.calls).extracting(List::size).containsExactly(25, 5);
        assertThat(summary.success()).isEqualTo(30);
        assertThat(current.getStatus()).isEqualTo(MeteringStatus.PENDING);
        UsageRecord sent = client.calls.get(0).get(0);
        assertThat(sent.licenseArn()).isEqualTo(subscriber.getLicenseArn());
        assertThat(sent.customerIdentifier()).as("신규 연동: CustomerIdentifier 미사용").isNull();
        assertThat(sent.timestamp() % 3600).isZero();
        assertThat(records.findAll()).filteredOn(r -> r.getStatus() == MeteringStatus.SUCCESS)
                .allSatisfy(r -> {
                    assertThat(r.getMeteringRecordId()).startsWith("mrec-");
                    assertThat(r.getAttempts()).isEqualTo(1);
                    assertThat(r.getSentAt()).isEqualTo(NOW);
                });

        assertThat(job.run().claimed()).as("두 번째 실행에는 보낼 것이 없다").isZero();
    }

    @Test
    @DisplayName("UnprocessedRecords는 PENDING으로 되돌려 재시도하고, 5번째 시도 후 FAILED")
    void unprocessedRetriesUntilLimit() {
        MeteringRecord record = pending("analysis_run", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 3);
        client.responder = recs -> new BatchMeterUsageResult(List.of(), recs);

        job.run();
        assertThat(record.getStatus()).isEqualTo(MeteringStatus.PENDING);
        assertThat(record.getAttempts()).isEqualTo(1);
        assertThat(record.getLastError()).isEqualTo("UnprocessedRecords");

        for (int i = 0; i < 4; i++) {
            job.run();
        }
        assertThat(record.getStatus()).isEqualTo(MeteringStatus.FAILED);
        assertThat(record.getAttempts()).isEqualTo(5);
        assertThat(record.getLastError()).startsWith("MAX_ATTEMPTS");
        assertThat(client.calls).hasSize(5);
    }

    @Test
    @DisplayName("DuplicateRecord → DUPLICATE, CustomerNotSubscribed → FAILED + 구독 만료(METERING_REJECTED)")
    void duplicateAndNotSubscribed() {
        MeteringRecord dup = pending("analysis_run", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 3);
        MeteringRecord rejected = pending("data_gb", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 4);
        client.responder = recs -> new BatchMeterUsageResult(List.of(
                new UsageRecordResult(null, MeteringApi.DUPLICATE_RECORD, recs.get(0)),
                new UsageRecordResult(null, MeteringApi.CUSTOMER_NOT_SUBSCRIBED, recs.get(1))), List.of());

        MeteringJob.RunSummary summary = job.run();

        assertThat(summary.duplicate()).isEqualTo(1);
        assertThat(summary.customerNotSubscribed()).isEqualTo(1);
        assertThat(dup.getStatus()).isEqualTo(MeteringStatus.DUPLICATE);
        assertThat(rejected.getStatus()).isEqualTo(MeteringStatus.FAILED);
        assertThat(subscriber.isSubscriptionExpired()).isTrue();
        assertThat(subscriber.getExpiredReason()).isEqualTo(StatusReason.METERING_REJECTED);
    }

    @Test
    @DisplayName("재시도 가능한 호출 오류(Throttling)는 배치 전체를 PENDING으로 되돌린다")
    void retryableCallFailure() {
        MeteringRecord a = pending("analysis_run", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 1);
        MeteringRecord b = pending("data_gb", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 1);
        client.responder = recs -> {
            throw new MarketplaceApiException("BatchMeterUsage", "ThrottlingException", 400, "Rate exceeded", null);
        };

        MeteringJob.RunSummary summary = job.run();

        assertThat(summary.retryLater()).isEqualTo(2);
        assertThat(summary.errors()).containsExactly("ThrottlingException (2건)");
        assertThat(List.of(a.getStatus(), b.getStatus())).containsOnly(MeteringStatus.PENDING);
        assertThat(a.getLastError()).isEqualTo("ThrottlingException");
    }

    @Test
    @DisplayName("재시도 불가 요청 오류는 한 건씩 다시 보내 문제 레코드만 FAILED 처리한다")
    void isolatesPoisonRecord() {
        MeteringRecord good1 = pending("analysis_run", CURRENT_HOUR.minus(2, ChronoUnit.HOURS), 1);
        MeteringRecord poison = pending("data_gb", CURRENT_HOUR.minus(2, ChronoUnit.HOURS), 1);
        MeteringRecord good2 = pending("analysis_run", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 1);
        client.responder = recs -> {
            if (recs.stream().anyMatch(r -> r.dimension().equals("data_gb"))) {
                throw new MarketplaceApiException("BatchMeterUsage", "InvalidUsageDimensionException", 400, "bad", null);
            }
            return FakeClient.allSuccess(recs);
        };

        job.run();

        assertThat(client.calls).extracting(List::size).containsExactly(3, 1, 1, 1);
        assertThat(good1.getStatus()).isEqualTo(MeteringStatus.SUCCESS);
        assertThat(good2.getStatus()).isEqualTo(MeteringStatus.SUCCESS);
        assertThat(poison.getStatus()).isEqualTo(MeteringStatus.FAILED);
        assertThat(poison.getLastError()).isEqualTo("InvalidUsageDimensionException");
    }

    @Test
    @DisplayName("24시간 한도에 걸리는 레코드는 보내지 않고 FAILED(TIMESTAMP_OUT_OF_BOUNDS)")
    void blocksTooOldRecords() {
        MeteringRecord old = pending("analysis_run", NOW.minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS), 5);
        MeteringRecord fresh = pending("analysis_run", CURRENT_HOUR.minus(23, ChronoUnit.HOURS), 5);

        job.run();

        assertThat(old.getStatus()).isEqualTo(MeteringStatus.FAILED);
        assertThat(old.getLastError()).startsWith("TIMESTAMP_OUT_OF_BOUNDS");
        assertThat(old.getSentAt()).isNull();
        assertThat(fresh.getStatus()).isEqualTo(MeteringStatus.SUCCESS);
        assertThat(client.calls).hasSize(1).first().satisfies(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    @DisplayName("사용량형 구독은 사용량이 없는 마감 시간대(최근 3시간)에 0 레코드를 만들어 보낸다")
    void createsZeroUsageRecords() {
        subscriber.setTermStartAt(NOW.minus(10, ChronoUnit.HOURS));
        pending("data_gb", CURRENT_HOUR.minus(2, ChronoUnit.HOURS), 7);

        MeteringJob.RunSummary summary = job.run();

        assertThat(summary.zeroRecordsCreated()).as("-3h, -1h (−2h는 기존 레코드 있음)").isEqualTo(2);
        assertThat(client.calls.get(0)).filteredOn(r -> r.quantity() == 0)
                .extracting(UsageRecord::dimension).containsOnly("analysis_run");
        assertThat(summary.success()).isEqualTo(3);

        assertThat(job.run().zeroRecordsCreated()).as("다시 실행해도 중복 생성하지 않음").isZero();
    }

    @Test
    @DisplayName("해지되었거나 구독 대기 중이면 0 레코드를 만들지 않는다")
    void noZeroRecordsForInactive() {
        subscriber.setTermStartAt(NOW.minus(10, ChronoUnit.HOURS));
        subscriber.setSubscriptionExpired(true);
        assertThat(job.run().zeroRecordsCreated()).isZero();

        subscriber.setSubscriptionExpired(false);
        subscriber.setSuccessfullySubscribed(false);
        assertThat(job.run().zeroRecordsCreated()).isZero();
    }

    @Test
    @DisplayName("실행 중에 다시 실행하면 409 METERING_ALREADY_RUNNING")
    void preventsConcurrentRuns() throws Exception {
        pending("analysis_run", CURRENT_HOUR.minus(1, ChronoUnit.HOURS), 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.responder = recs -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeClient.allSuccess(recs);
        };

        CompletableFuture<MeteringJob.RunSummary> first = CompletableFuture.supplyAsync(job::run);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(job::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.METERING_ALREADY_RUNNING);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).success()).isEqualTo(1);
    }
}
