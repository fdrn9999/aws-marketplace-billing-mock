package io.github.fdrn9999.marketplace.mockaws;

import org.springframework.http.HttpStatus;

/** Mock AWS API가 반환하는 AWS 형식 오류 ({@code __type} = AWS 예외 이름). */
public class MockAwsException extends RuntimeException {

    private final String type;
    private final HttpStatus status;

    public MockAwsException(String type, HttpStatus status, String message) {
        super(message);
        this.type = type;
        this.status = status;
    }

    public String type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    public static MockAwsException clientError(String type, String message) {
        return new MockAwsException(type, HttpStatus.BAD_REQUEST, message);
    }

    public static MockAwsException throttling() {
        return new MockAwsException("ThrottlingException", HttpStatus.BAD_REQUEST, "Rate exceeded");
    }

    public static MockAwsException internalError() {
        return new MockAwsException("InternalServiceErrorException", HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error has occurred. Retry your request.");
    }
}
