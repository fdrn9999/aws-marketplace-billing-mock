package io.github.fdrn9999.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulatedClockTest {

    private final Instant base = Instant.parse("2026-09-17T05:30:00Z");
    private final SimulatedClock clock = new SimulatedClock(Clock.fixed(base, ZoneOffset.UTC));

    @Test
    @DisplayName("시계를 앞으로 이동하면 now와 현재 시간대 시작 시각이 함께 바뀐다")
    void advancesForward() {
        clock.advance(Duration.ofHours(1));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-09-17T06:30:00Z"));
        assertThat(clock.currentHourStart()).isEqualTo(Instant.parse("2026-09-17T06:00:00Z"));
    }

    @Test
    @DisplayName("0 또는 음수 이동은 INVALID_CLOCK_OPERATION으로 거부한다")
    void rejectsBackwards() {
        assertThatThrownBy(() -> clock.advance(Duration.ofHours(-1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_CLOCK_OPERATION);
        assertThatThrownBy(() -> clock.advance(Duration.ZERO)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("reset은 오프셋을 0으로 되돌린다")
    void resets() {
        clock.advance(Duration.ofDays(31));
        clock.reset();
        assertThat(clock.now()).isEqualTo(base);
    }
}
