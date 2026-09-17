package io.github.fdrn9999.marketplace.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/** Idempotency-Key 처리 결과 보관소. 같은 키로 같은 요청이 다시 오면 저장된 응답을 그대로 돌려준다. */
@Repository
public class IdempotencyRepository {

    /** @param fingerprint 요청 내용 요약. 같은 키에 다른 내용이 오면 충돌로 처리한다 */
    public record Entry(String fingerprint, Object response) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Entry> find(String scope, String key) {
        return Optional.ofNullable(entries.get(scope + "|" + key));
    }

    public void save(String scope, String key, Entry entry) {
        entries.put(scope + "|" + key, entry);
    }

    public void clear() {
        entries.clear();
    }
}
