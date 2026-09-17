package io.github.fdrn9999.marketplace.common;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
