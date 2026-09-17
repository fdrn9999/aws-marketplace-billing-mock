package io.github.fdrn9999.marketplace.store;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.UsageEvent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판매자(앱) 측 시드 데이터 로더: {@code classpath:mock-data/app/*.json}.
 * 미터링 레코드는 따로 적지 않고 사용량 이벤트를 시간 버킷으로 합산해 만든다. 그래서 두 데이터가 항상 일치한다.
 */
@Component
@Order(1)
public class AppSeedLoader implements SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(AppSeedLoader.class);
    private static final String BASE = "mock-data/app/";

    private final JsonMapper jsonMapper;
    private final SimulatedClock clock;
    private final ProductRepository products;
    private final SubscriberRepository subscribers;
    private final UsageEventRepository usageEvents;
    private final MeteringRecordRepository meteringRecords;
    private final OnboardingSessionRepository onboardingSessions;

    public AppSeedLoader(JsonMapper jsonMapper, SimulatedClock clock, ProductRepository products,
            SubscriberRepository subscribers, UsageEventRepository usageEvents,
            MeteringRecordRepository meteringRecords, OnboardingSessionRepository onboardingSessions) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.products = products;
        this.subscribers = subscribers;
        this.usageEvents = usageEvents;
        this.meteringRecords = meteringRecords;
        this.onboardingSessions = onboardingSessions;
    }

    @Override
    public void load() {
        Instant now = clock.now();
        products.replaceAll(read("products.json", new TypeReference<List<Product>>() { }));

        subscribers.clear();
        onboardingSessions.clear();
        for (SubscriberSeed seed : read("subscribers.json", new TypeReference<List<SubscriberSeed>>() { })) {
            subscribers.save(seed.toSubscriber(now));
        }

        usageEvents.clear();
        meteringRecords.clear();
        Map<String, MeteringRecord> buckets = new LinkedHashMap<>();
        for (UsageEventSeed seed : read("usage-events.json", new TypeReference<List<UsageEventSeed>>() { })) {
            Subscriber subscriber = subscribers.findById(seed.subscriberId())
                    .orElseThrow(() -> new IllegalStateException("시드 사용량의 구독자가 없습니다: " + seed.subscriberId()));
            Instant occurredAt = RelativeTime.parse(seed.occurredAt(), now);
            usageEvents.save(new UsageEvent(Ids.next("evt"), subscriber.getSubscriberId(), subscriber.getLicenseArn(),
                    seed.dimension(), seed.quantity(), seed.includedQuantity(), seed.meteredQuantity(), null, occurredAt));
            if (seed.meteredQuantity() > 0) {
                addToBucket(buckets, subscriber, seed, occurredAt, now);
            }
        }
        buckets.values().forEach(meteringRecords::save);

        log.info("앱 시드 로드 완료: 상품 {}, 구독자 {}, 미터링 버킷 {}",
                products.findAll().size(), subscribers.findAll().size(), buckets.size());
    }

    private void addToBucket(Map<String, MeteringRecord> buckets, Subscriber subscriber, UsageEventSeed seed,
            Instant occurredAt, Instant now) {
        Instant hourStart = occurredAt.truncatedTo(ChronoUnit.HOURS);
        String key = MeteringRecord.key(subscriber.getLicenseArn(), seed.dimension(), hourStart);
        MeteringRecord record = buckets.computeIfAbsent(key, k -> {
            MeteringRecord r = new MeteringRecord();
            r.setId(Ids.next("mr"));
            r.setSubscriberId(subscriber.getSubscriberId());
            r.setLicenseArn(subscriber.getLicenseArn());
            r.setCustomerAWSAccountId(subscriber.getCustomerAWSAccountId());
            r.setProductCode(subscriber.getProductCode());
            r.setDimension(seed.dimension());
            r.setHourStart(hourStart);
            r.setCreatedAt(occurredAt);
            MeteringStatus status = seed.meteringStatus() == null ? MeteringStatus.PENDING : seed.meteringStatus();
            r.setStatus(status);
            if (status == MeteringStatus.SUCCESS) {
                r.setMeteringRecordId(Ids.next("seed-record"));
                r.setAttempts(1);
                r.setSentAt(hourStart.plus(1, ChronoUnit.HOURS).plusSeconds(120));
            }
            return r;
        });
        record.setQuantity(record.getQuantity() + seed.meteredQuantity());
        record.setUpdatedAt(now);
    }

    private <T> T read(String file, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource(BASE + file).getInputStream()) {
            return jsonMapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("시드 파일을 읽을 수 없습니다: " + BASE + file, e);
        }
    }

    record EntitlementSeed(String dimension, Integer quantity, String expirationDate) {
    }

    record SubscriberSeed(
            String subscriberId, String licenseArn, String customerAWSAccountId, String customerIdentifier,
            String productCode, String companyName, String contactPerson, String contactPhone, String contactEmail,
            Boolean successfullyRegistered, Boolean successfullySubscribed, Boolean subscriptionExpired,
            StatusReason expiredReason, Boolean freeTrialTermPresent, List<EntitlementSeed> entitlements,
            String termStartAt, String lastEntitlementSyncAt, String createdAt) {

        Subscriber toSubscriber(Instant now) {
            Subscriber s = new Subscriber();
            s.setSubscriberId(subscriberId);
            s.setLicenseArn(licenseArn);
            s.setCustomerAWSAccountId(customerAWSAccountId);
            s.setCustomerIdentifier(customerIdentifier);
            s.setProductCode(productCode);
            s.setCompanyName(companyName);
            s.setContactPerson(contactPerson);
            s.setContactPhone(contactPhone);
            s.setContactEmail(contactEmail);
            s.setSuccessfullyRegistered(Boolean.TRUE.equals(successfullyRegistered));
            s.setSuccessfullySubscribed(Boolean.TRUE.equals(successfullySubscribed));
            s.setSubscriptionExpired(Boolean.TRUE.equals(subscriptionExpired));
            s.setExpiredReason(expiredReason);
            s.setFreeTrialTermPresent(Boolean.TRUE.equals(freeTrialTermPresent));
            s.setEntitlements(entitlements == null ? List.of() : entitlements.stream()
                    .map(e -> new EntitlementSnapshot(e.dimension(), e.quantity(), RelativeTime.parse(e.expirationDate(), now)))
                    .toList());
            s.setTermStartAt(RelativeTime.parse(termStartAt, now));
            s.setLastEntitlementSyncAt(RelativeTime.parse(lastEntitlementSyncAt, now));
            s.setCreatedAt(RelativeTime.parse(createdAt, now));
            s.setUpdatedAt(now);
            return s;
        }
    }

    record UsageEventSeed(String subscriberId, String dimension, long quantity, long includedQuantity,
            long meteredQuantity, String occurredAt, MeteringStatus meteringStatus) {
    }
}
