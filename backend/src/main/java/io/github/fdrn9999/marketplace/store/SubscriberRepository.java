package io.github.fdrn9999.marketplace.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import io.github.fdrn9999.marketplace.domain.Subscriber;

/** QuickStart의 {@code AWSMarketplaceSubscribers} DynamoDB 테이블을 대신하는 인메모리 저장소. */
@Repository
public class SubscriberRepository {

    private final Map<String, Subscriber> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByLicenseArn = new ConcurrentHashMap<>();

    public Optional<Subscriber> findById(String subscriberId) {
        return subscriberId == null ? Optional.empty() : Optional.ofNullable(byId.get(subscriberId));
    }

    public Optional<Subscriber> findByLicenseArn(String licenseArn) {
        if (licenseArn == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(idByLicenseArn.get(licenseArn)).map(byId::get);
    }

    public List<Subscriber> findAll() {
        return byId.values().stream()
                .sorted(Comparator.comparing(Subscriber::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Subscriber::getSubscriberId))
                .toList();
    }

    public synchronized Subscriber save(Subscriber subscriber) {
        String existingId = idByLicenseArn.get(subscriber.getLicenseArn());
        if (existingId != null && !existingId.equals(subscriber.getSubscriberId())) {
            throw new IllegalStateException("licenseArn already belongs to " + existingId);
        }
        byId.put(subscriber.getSubscriberId(), subscriber);
        idByLicenseArn.put(subscriber.getLicenseArn(), subscriber.getSubscriberId());
        return subscriber;
    }

    public synchronized void clear() {
        byId.clear();
        idByLicenseArn.clear();
    }
}
