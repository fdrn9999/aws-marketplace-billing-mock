package io.github.fdrn9999.marketplace.mockaws;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.awsapi.AwsErrorBody;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.SimulatedClock;

/** Mock AWS API 호출 기록(최근 200건). UI의 "AWS 호출 로그"에서 요청/응답을 그대로 보여준다. */
@Component
public class MockAwsCallLog {

    private static final int MAX_ENTRIES = 200;

    /** @param errorType 실패 시 AWS 예외 이름 */
    public record Entry(String id, Instant at, String api, String licenseArn, int httpStatus, String errorType,
            Object request, Object response, long durationMs) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final SimulatedClock clock;

    public MockAwsCallLog(SimulatedClock clock) {
        this.clock = clock;
    }

    /** API 실행을 감싸서 결과나 오류를 기록한다. 예외는 그대로 다시 던진다. */
    public <T> T record(String api, String licenseArn, Object request, Supplier<T> action) {
        Instant at = clock.now();
        long start = System.nanoTime();
        try {
            T result = action.get();
            add(new Entry(Ids.next("call"), at, api, licenseArn, 200, null, request, result, elapsedMs(start)));
            return result;
        } catch (MockAwsException e) {
            add(new Entry(Ids.next("call"), at, api, licenseArn, e.status().value(), e.type(), request,
                    new AwsErrorBody(e.type(), e.getMessage()), elapsedMs(start)));
            throw e;
        } catch (RuntimeException e) {
            add(new Entry(Ids.next("call"), at, api, licenseArn, 500, "InternalServiceErrorException", request,
                    new AwsErrorBody("InternalServiceErrorException", e.getMessage()), elapsedMs(start)));
            throw e;
        }
    }

    public synchronized List<Entry> recent(String licenseArn, int limit) {
        return entries.stream()
                .filter(e -> licenseArn == null || licenseArn.equals(e.licenseArn()) || e.licenseArn() == null)
                .limit(Math.max(1, Math.min(limit, MAX_ENTRIES)))
                .toList();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private synchronized void add(Entry entry) {
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
