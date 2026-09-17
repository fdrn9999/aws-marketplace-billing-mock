package io.github.fdrn9999.marketplace.access;

import org.springframework.util.StringUtils;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;

/**
 * [데모 전용 인증] 요청 헤더 {@code X-Customer-Id}로 고객을 식별한다.
 * 누구나 다른 고객으로 위장할 수 있으므로 실제 서비스에서는 앱 자체 로그인 사용자와 구독자(licenseArn)를 매핑해야 한다.
 */
public final class CustomerHeader {

    public static final String NAME = "X-Customer-Id";

    private CustomerHeader() {
    }

    public static String require(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return headerValue.trim();
    }
}
