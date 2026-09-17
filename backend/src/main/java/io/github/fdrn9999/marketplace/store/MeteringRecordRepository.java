package io.github.fdrn9999.marketplace.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;

/** QuickStart의 {@code AWSMarketplaceMeteringRecords} DynamoDB 테이블을 대신하는 인메모리 저장소. */
@Repository
public class MeteringRecordRepository {

    private static final Comparator<MeteringRecord> NEWEST_FIRST = Comparator
            .comparing(MeteringRecord::getHourStart).reversed()
            .thenComparing(MeteringRecord::getDimension);

    /** 키 = {@link MeteringRecord#key()} */
    private final Map<String, MeteringRecord> records = new ConcurrentHashMap<>();

    public Optional<MeteringRecord> findByKey(String key) {
        return Optional.ofNullable(records.get(key));
    }

    public MeteringRecord save(MeteringRecord record) {
        records.put(record.key(), record);
        return record;
    }

    public List<MeteringRecord> findAll() {
        return records.values().stream().sorted(NEWEST_FIRST).toList();
    }

    public List<MeteringRecord> findBySubscriber(String subscriberId) {
        return records.values().stream()
                .filter(r -> r.getSubscriberId().equals(subscriberId))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    public List<MeteringRecord> findByStatus(MeteringStatus status) {
        return records.values().stream()
                .filter(r -> r.getStatus() == status)
                .sorted(Comparator.comparing(MeteringRecord::getHourStart))
                .toList();
    }

    public void clear() {
        records.clear();
    }
}
