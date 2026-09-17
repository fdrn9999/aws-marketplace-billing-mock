package io.github.fdrn9999.marketplace.metering;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.MeteringRecordRepository;

/**
 * 미터링 대상 수량을 (licenseArn, 차원, 시간) 버킷에 누적한다.
 * 호출자는 구독 단위 락(KeyedLocks)을 잡은 상태에서 호출해야 한다.
 */
@Component
public class MeteringBuckets {

    private final MeteringRecordRepository records;

    public MeteringBuckets(MeteringRecordRepository records) {
        this.records = records;
    }

    public MeteringRecord add(Subscriber subscriber, String dimension, long quantity, Instant occurredAt) {
        Instant hourStart = occurredAt.truncatedTo(ChronoUnit.HOURS);
        String key = MeteringRecord.key(subscriber.getLicenseArn(), dimension, hourStart);
        MeteringRecord record = records.findByKey(key).orElseGet(() -> newRecord(subscriber, dimension, hourStart, occurredAt));
        if (record.getStatus() != MeteringStatus.PENDING) {
            // 미터링 작업은 마감된 시간대만 가져가므로 현재 시간대 버킷이 이미 전송 중이면 시계/락 규칙이 깨진 것이다
            throw new IllegalStateException("이미 전송 단계에 들어간 버킷에는 사용량을 더할 수 없습니다: " + key);
        }
        record.setQuantity(record.getQuantity() + quantity);
        record.setUpdatedAt(occurredAt);
        return records.save(record);
    }

    static MeteringRecord newRecord(Subscriber subscriber, String dimension, Instant hourStart, Instant now) {
        MeteringRecord r = new MeteringRecord();
        r.setId(Ids.next("mr"));
        r.setSubscriberId(subscriber.getSubscriberId());
        r.setLicenseArn(subscriber.getLicenseArn());
        r.setCustomerAWSAccountId(subscriber.getCustomerAWSAccountId());
        r.setProductCode(subscriber.getProductCode());
        r.setDimension(dimension);
        r.setHourStart(hourStart);
        r.setQuantity(0);
        r.setStatus(MeteringStatus.PENDING);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }
}
