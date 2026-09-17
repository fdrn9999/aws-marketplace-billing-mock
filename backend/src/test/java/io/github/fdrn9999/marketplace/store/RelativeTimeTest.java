package io.github.fdrn9999.marketplace.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelativeTimeTest {

    private final Instant now = Instant.parse("2026-09-17T05:30:00Z");

    @Test
    @DisplayName("now±N(d/h/m/s) 상대 표기를 기준 시각에서 계산한다")
    void parsesRelativeExpressions() {
        assertThat(RelativeTime.parse("now", now)).isEqualTo(now);
        assertThat(RelativeTime.parse("now+6d", now)).isEqualTo(now.plus(Duration.ofDays(6)));
        assertThat(RelativeTime.parse("now-3h", now)).isEqualTo(now.minus(Duration.ofHours(3)));
        assertThat(RelativeTime.parse("now-15m", now)).isEqualTo(now.minus(Duration.ofMinutes(15)));
        assertThat(RelativeTime.parse("now+30s", now)).isEqualTo(now.plusSeconds(30));
    }

    @Test
    @DisplayName("절대 시각(ISO-8601)과 빈 값도 처리한다")
    void parsesAbsoluteAndBlank() {
        assertThat(RelativeTime.parse("2026-01-01T00:00:00Z", now)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(RelativeTime.parse(null, now)).isNull();
        assertThat(RelativeTime.parse(" ", now)).isNull();
    }

    @Test
    @DisplayName("형식이 틀리면 예외가 발생한다")
    void rejectsGarbage() {
        assertThatThrownBy(() -> RelativeTime.parse("tomorrow", now)).isInstanceOf(RuntimeException.class);
    }
}
