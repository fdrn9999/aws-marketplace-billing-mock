package io.github.fdrn9999.marketplace.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    static AppProperties props(boolean demoMode, String secret) {
        return new AppProperties(demoMode, "", secret,
                new AppProperties.MockAws("", Duration.ofHours(1)),
                new AppProperties.Entitlement(Duration.ofMinutes(15), Duration.ofHours(24)),
                new AppProperties.Metering(25, 5, 3, 3, Duration.ofHours(24), Duration.ZERO));
    }

    @Test
    @DisplayName("데모 모드가 아니면 저장소에 공개된 기본 이벤트 비밀값으로는 기동하지 않는다")
    void rejectsDefaultSecretOutsideDemo() {
        assertThatThrownBy(() -> props(false, AppProperties.DEFAULT_EVENT_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_EVENT_SECRET");
        assertThatThrownBy(() -> props(false, " ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("데모 모드에서는 기본값을 허용하고, 운영 모드에서는 별도 비밀값을 쓰면 된다")
    void acceptsValidCombinations() {
        assertThatCode(() -> props(true, AppProperties.DEFAULT_EVENT_SECRET)).doesNotThrowAnyException();
        assertThatCode(() -> props(false, "a-real-secret-from-env")).doesNotThrowAnyException();
    }
}
