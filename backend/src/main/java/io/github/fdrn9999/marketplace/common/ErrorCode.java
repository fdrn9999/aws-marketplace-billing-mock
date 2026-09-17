package io.github.fdrn9999.marketplace.common;

import org.springframework.http.HttpStatus;

/** 앱 API의 에러 계약. 프론트엔드는 code 값으로 분기하고 message를 사용자에게 보여준다. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "고객을 식별할 수 없습니다 (X-Customer-Id 헤더 없음)"),

    NOT_SUBSCRIBED(HttpStatus.FORBIDDEN, "유효한 AWS Marketplace 구독이 없습니다"),
    SUBSCRIPTION_EXPIRED(HttpStatus.FORBIDDEN, "AWS Marketplace 구독이 만료되었습니다"),
    QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "계약 수량을 모두 사용했습니다"),

    INVALID_REGISTRATION_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않은 등록 토큰입니다"),
    EXPIRED_REGISTRATION_TOKEN(HttpStatus.BAD_REQUEST, "등록 토큰이 만료되었거나 이미 사용되었습니다"),
    ONBOARDING_SESSION_EXPIRED(HttpStatus.BAD_REQUEST, "등록 세션이 만료되었거나 이미 완료되었습니다"),
    UNKNOWN_PRODUCT(HttpStatus.BAD_REQUEST, "이 서비스에서 판매하지 않는 제품입니다"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    INVALID_CLOCK_OPERATION(HttpStatus.BAD_REQUEST, "허용되지 않는 시계 조작입니다"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),

    METERING_ALREADY_RUNNING(HttpStatus.CONFLICT, "미터링 작업이 이미 실행 중입니다"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 Idempotency-Key가 다른 요청에 이미 사용되었습니다"),

    MARKETPLACE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AWS Marketplace API를 일시적으로 사용할 수 없습니다"),
    ENTITLEMENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "계약(Entitlement) 정보를 확인할 수 없습니다"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
