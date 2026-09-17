package io.github.fdrn9999.marketplace.store;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시드 파일의 시간 값은 기동 시점 기준 상대 표기({@code "now"}, {@code "now+6d"}, {@code "now-3h"}, {@code "now-15m"})로 적는다.
 * 그래야 언제 실행해도 "만료 임박", "이미 만료" 같은 시연 시나리오가 유지된다. ISO-8601 절대 시각도 허용한다.
 */
public final class RelativeTime {

    private static final Pattern RELATIVE = Pattern.compile("^now(?:([+-])(\\d+)([dhms]))?$");

    private RelativeTime() {
    }

    public static Instant parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = RELATIVE.matcher(value.trim());
        if (!m.matches()) {
            return Instant.parse(value.trim());
        }
        if (m.group(1) == null) {
            return now;
        }
        long amount = Long.parseLong(m.group(2));
        Duration delta = switch (m.group(3)) {
            case "d" -> Duration.ofDays(amount);
            case "h" -> Duration.ofHours(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofSeconds(amount);
        };
        return "+".equals(m.group(1)) ? now.plus(delta) : now.minus(delta);
    }
}
