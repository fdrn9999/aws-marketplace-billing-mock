package io.github.fdrn9999.marketplace.common;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;

/**
 * 데모 초기화와 일반 처리의 경계.
 * 앱 요청과 미터링 작업은 읽기 잠금을, 초기화는 쓰기 잠금을 잡는다. 그래서 초기화는 진행 중인 처리가 끝난 뒤에만 실행되고,
 * 초기화 전에 읽어 둔 객체가 초기화 뒤에 다시 저장되어 시드 상태를 덮어쓰는 일이 없다.
 * (Mock AWS 요청은 잡지 않는다. 앱 요청이 Mock AWS를 HTTP로 호출하므로 같이 잡으면 초기화 대기 중에 교착될 수 있다)
 */
@Component
public class DemoStateLock {

    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public <T, E extends Exception> T read(Work<T, E> work) throws E {
        lock.readLock().lock();
        try {
            return work.run();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 진행 중인 처리가 timeout 안에 끝나지 않으면 409 DEMO_BUSY */
    public void write(Runnable work, Duration timeout) {
        boolean acquired;
        try {
            acquired = lock.writeLock().tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.DEMO_BUSY);
        }
        if (!acquired) {
            throw new ApiException(ErrorCode.DEMO_BUSY);
        }
        try {
            work.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 테스트용: 현재 읽기 잠금을 잡은 처리 수 */
    public int activeReaders() {
        return lock.getReadLockCount();
    }
}
