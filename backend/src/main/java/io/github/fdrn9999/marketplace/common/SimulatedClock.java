package io.github.fdrn9999.marketplace.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * 실제 시각에 "앞으로 감기" 오프셋을 더한 시뮬레이션 시계.
 * 시간 단위 미터링과 계약 만료를 기다리지 않고 시연하기 위해 사용한다.
 * 앱과 Mock AWS의 모든 시간 규칙은 이 시계를 기준으로 한다.
 */
@Component
public class SimulatedClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public SimulatedClock() {
        this(Clock.systemUTC());
    }

    public SimulatedClock(Clock base) {
        this.base = base;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("SimulatedClock은 항상 UTC입니다");
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    public Instant now() {
        return instant();
    }

    /** {@code now()}가 속한 시간대(정각)의 시작 시각(UTC). */
    public Instant currentHourStart() {
        return now().truncatedTo(ChronoUnit.HOURS);
    }

    public Duration offset() {
        return offset.get();
    }

    /** 시간을 앞으로만 이동한다. 이미 마감된 미터링 시간대가 다시 열리지 않도록 역행은 거부한다. */
    public Instant advance(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new ApiException(ErrorCode.INVALID_CLOCK_OPERATION, "시계는 앞으로만 이동할 수 있습니다");
        }
        offset.updateAndGet(current -> current.plus(duration));
        return now();
    }

    /** 실제 시각으로 되돌린다. 전체 데이터 초기화와 함께만 사용한다. */
    public void reset() {
        offset.set(Duration.ZERO);
    }
}
