package io.github.fdrn9999.marketplace.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 설정된 주소가 없으면 "이 서버 자신"의 주소를 돌려준다.
 * Mock AWS와 앱이 같은 프로세스에 있어도 HTTP로 통신하게 하려는 용도다(테스트의 랜덤 포트도 지원).
 */
@Component
public class LocalServerUrl {

    private final Environment environment;

    public LocalServerUrl(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }
}
