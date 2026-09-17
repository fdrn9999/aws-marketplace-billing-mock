package io.github.fdrn9999.marketplace.common;

import java.util.Map;

/** 앱 API의 모든 오류 응답 본문: {@code {"error": {code, message, details, requestId}}} */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, Map<String, Object> details, String requestId) {
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details, String requestId) {
        return new ErrorResponse(new Body(code.name(), message, details, requestId));
    }
}
