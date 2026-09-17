package io.github.fdrn9999.marketplace.client;

import java.util.Set;

/**
 * AWS Marketplace API 호출 실패.
 *
 * @see #awsErrorType() AWS 예외 이름 (예: InvalidTokenException). 네트워크 오류면 {@code NetworkError}
 */
public class MarketplaceApiException extends RuntimeException {

    public static final String NETWORK_ERROR = "NetworkError";

    /** 재시도하면 성공할 수 있는 오류 */
    private static final Set<String> RETRYABLE = Set.of(
            "ThrottlingException", "InternalServiceErrorException", NETWORK_ERROR);

    private final String api;
    private final String awsErrorType;
    private final int httpStatus;

    public MarketplaceApiException(String api, String awsErrorType, int httpStatus, String message, Throwable cause) {
        super(api + " 실패: " + awsErrorType + " - " + message, cause);
        this.api = api;
        this.awsErrorType = awsErrorType;
        this.httpStatus = httpStatus;
    }

    public String api() {
        return api;
    }

    public String awsErrorType() {
        return awsErrorType;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return RETRYABLE.contains(awsErrorType) || httpStatus >= 500;
    }
}
