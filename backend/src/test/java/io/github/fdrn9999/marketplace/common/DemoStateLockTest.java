package io.github.fdrn9999.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemoStateLockTest {

    private final DemoStateLock lock = new DemoStateLock();

    /** 읽기 잠금을 잡고 release 신호가 올 때까지 붙잡고 있는 "진행 중인 요청" */
    private CompletableFuture<Void> holdReadLock(CountDownLatch started, CountDownLatch release, Runnable onDone) {
        return CompletableFuture.runAsync(() -> {
            try {
                lock.read(() -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    onDone.run();
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    @DisplayName("초기화(쓰기)는 진행 중인 요청(읽기)이 끝난 뒤에 실행된다")
    void resetWaitsForInFlightWork() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);

        CompletableFuture<Void> reader = holdReadLock(readerStarted, releaseReader, () -> order.add("request-done"));
        assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> reset = CompletableFuture.runAsync(
                () -> lock.write(() -> order.add("reset"), Duration.ofSeconds(5)));
        Thread.sleep(200);
        assertThat(reset).as("요청이 끝나기 전에는 초기화하지 않는다").isNotDone();

        releaseReader.countDown();
        reader.get(5, TimeUnit.SECONDS);
        reset.get(5, TimeUnit.SECONDS);
        assertThat(order).containsExactly("request-done", "reset");
    }

    @Test
    @DisplayName("요청이 제한 시간 안에 끝나지 않으면 초기화는 409 DEMO_BUSY")
    void resetTimesOut() throws Exception {
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CompletableFuture<Void> reader = holdReadLock(readerStarted, releaseReader, () -> { });
        assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> lock.write(() -> { }, Duration.ofMillis(100)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.DEMO_BUSY);

        releaseReader.countDown();
        reader.get(5, TimeUnit.SECONDS);
        assertThat(lock.activeReaders()).isZero();
    }
}
