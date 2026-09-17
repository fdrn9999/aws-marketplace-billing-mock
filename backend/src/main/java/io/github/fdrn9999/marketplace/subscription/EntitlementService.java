package io.github.fdrn9999.marketplace.subscription;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.Entitlement;
import io.github.fdrn9999.marketplace.client.MarketplaceApiException;
import io.github.fdrn9999.marketplace.client.MarketplaceClient;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.domain.EntitlementSnapshot;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;

/**
 * GetEntitlements 결과를 구독자 레코드에 동기화한다 (QuickStart EntitlementSQSHandler 역할).
 *
 * <p>캐시 정책
 * <ul>
 *   <li>캐시가 {@code refresh-after}보다 오래되면 조회 시 재동기화를 시도한다</li>
 *   <li>재동기화에 실패해도 마지막 동기화가 {@code max-staleness} 이내면 캐시를 쓰고 stale로 표시한다</li>
 *   <li>한 번도 동기화하지 못했거나 허용 기간을 넘기면 신뢰하지 않는다(fail-closed)</li>
 * </ul>
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    /**
     * @param trusted 캐시를 권한 판단에 써도 되는지
     * @param stale   최신 동기화에 실패해 이전 캐시를 쓰고 있는지
     * @param error   마지막 동기화 실패 사유 (AWS 오류 이름)
     */
    public record Freshness(boolean trusted, boolean stale, String error) {
        static final Freshness NOT_APPLICABLE = new Freshness(true, false, null);
    }

    private final MarketplaceClient client;
    private final SubscriberRepository subscribers;
    private final KeyedLocks locks;
    private final SimulatedClock clock;
    private final AppProperties properties;

    public EntitlementService(MarketplaceClient client, SubscriberRepository subscribers, KeyedLocks locks,
            SimulatedClock clock, AppProperties properties) {
        this.client = client;
        this.subscribers = subscribers;
        this.locks = locks;
        this.clock = clock;
        this.properties = properties;
    }

    /** 필요하면 재동기화하고, 캐시를 믿을 수 있는지 판단한다. */
    public Freshness ensureFresh(Subscriber subscriber, Product product) {
        if (!product.pricingModel().usesEntitlements()) {
            return Freshness.NOT_APPLICABLE;
        }
        Instant now = clock.now();
        Instant lastSync = subscriber.getLastEntitlementSyncAt();
        boolean needsRefresh = lastSync == null
                || Duration.between(lastSync, now).compareTo(properties.entitlement().refreshAfter()) > 0;
        if (!needsRefresh) {
            return new Freshness(true, false, null);
        }
        try {
            sync(subscriber, product);
            return new Freshness(true, false, null);
        } catch (MarketplaceApiException e) {
            Instant cachedAt = subscriber.getLastEntitlementSyncAt();
            boolean withinStaleness = cachedAt != null
                    && Duration.between(cachedAt, now).compareTo(properties.entitlement().maxStaleness()) <= 0;
            log.warn("Entitlement 재동기화 실패({}), 캐시 사용 가능={}", e.awsErrorType(), withinStaleness);
            return new Freshness(withinStaleness, true, e.awsErrorType());
        }
    }

    /**
     * GetEntitlements로 계약 정보를 다시 읽어 저장한다.
     * [Mock 정책] 만료일이 늘어나면(갱신) 새 계약 기간이 시작된 것으로 보고 termStartAt을 현재 시각으로 옮긴다.
     *
     * @throws MarketplaceApiException 재시도 후에도 조회에 실패한 경우
     */
    public void sync(Subscriber subscriber, Product product) {
        List<Entitlement> remote = client.getEntitlements(product.productCode(), subscriber.getLicenseArn());
        List<EntitlementSnapshot> snapshots = remote.stream()
                .map(e -> new EntitlementSnapshot(e.dimension(),
                        e.value() == null ? null : e.value().integerValue(),
                        e.expirationDate() == null ? null : Instant.ofEpochSecond(e.expirationDate())))
                .toList();
        locks.withLock(subscriber.getLicenseArn(), () -> {
            Instant now = clock.now();
            if (isRenewal(subscriber.getEntitlements(), snapshots) || subscriber.getTermStartAt() == null) {
                subscriber.setTermStartAt(now);
            }
            subscriber.setEntitlements(snapshots);
            subscriber.setLastEntitlementSyncAt(now);
            subscriber.setUpdatedAt(now);
            subscribers.save(subscriber);
        });
    }

    /** 이전에 계약이 있었고, 차원 중 하나라도 만료일이 뒤로 밀렸으면 갱신으로 본다. */
    static boolean isRenewal(List<EntitlementSnapshot> before, List<EntitlementSnapshot> after) {
        if (before.isEmpty()) {
            return false;
        }
        for (EntitlementSnapshot next : after) {
            for (EntitlementSnapshot prev : before) {
                if (Objects.equals(prev.dimension(), next.dimension()) && prev.expirationDate() != null
                        && next.expirationDate() != null && next.expirationDate().isAfter(prev.expirationDate())) {
                    return true;
                }
            }
        }
        return false;
    }
}
