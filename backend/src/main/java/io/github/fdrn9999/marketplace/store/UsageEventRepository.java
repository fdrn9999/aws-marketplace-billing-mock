package io.github.fdrn9999.marketplace.store;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Repository;

import io.github.fdrn9999.marketplace.domain.UsageEvent;

@Repository
public class UsageEventRepository {

    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    public UsageEvent save(UsageEvent event) {
        events.add(event);
        return event;
    }

    public List<UsageEvent> findBySubscriber(String subscriberId) {
        return events.stream()
                .filter(e -> e.subscriberId().equals(subscriberId))
                .sorted(Comparator.comparing(UsageEvent::occurredAt).reversed())
                .toList();
    }

    public List<UsageEvent> findBySubscriberSince(String subscriberId, Instant since) {
        return events.stream()
                .filter(e -> e.subscriberId().equals(subscriberId))
                .filter(e -> since == null || !e.occurredAt().isBefore(since))
                .toList();
    }

    /** {@code termStart} 이후 {@code dimension}의 계약 수량에서 이미 차감된 양. */
    public long sumIncluded(String subscriberId, String dimension, Instant termStart) {
        return findBySubscriberSince(subscriberId, termStart).stream()
                .filter(e -> e.dimension().equals(dimension))
                .mapToLong(UsageEvent::includedQuantity)
                .sum();
    }

    public List<UsageEvent> findByIdempotencyKey(String subscriberId, String idempotencyKey) {
        return events.stream()
                .filter(e -> e.subscriberId().equals(subscriberId))
                .filter(e -> idempotencyKey.equals(e.idempotencyKey()))
                .toList();
    }

    public Optional<UsageEvent> findLatest(String subscriberId) {
        return findBySubscriber(subscriberId).stream().findFirst();
    }

    public void clear() {
        events.clear();
    }
}
