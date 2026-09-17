package io.github.fdrn9999.marketplace.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * 구독(licenseArn) 단위 락.
 * 사용량 기록("남은 수량 확인 → 사용량 기록 → 시간 버킷 누적")과 미터링 작업의 claim 단계가 같은 락을 쓴다.
 * 그래서 동시 요청이 들어와도 계약 수량을 넘지 않고, 전송 대상으로 잡힌 버킷의 수량도 바뀌지 않는다.
 */
@Component
public class KeyedLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(String key, Runnable action) {
        withLock(key, () -> {
            action.run();
            return null;
        });
    }
}
