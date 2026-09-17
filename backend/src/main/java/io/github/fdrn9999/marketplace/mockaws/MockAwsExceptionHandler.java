package io.github.fdrn9999.marketplace.mockaws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.fdrn9999.marketplace.awsapi.AwsErrorBody;

/** Mock AWS 컨트롤러 전용 예외 처리: AWS JSON 프로토콜 형식({@code __type}, {@code message})으로 응답한다. */
@RestControllerAdvice(basePackageClasses = MockAwsExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MockAwsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MockAwsExceptionHandler.class);

    @ExceptionHandler(MockAwsException.class)
    public ResponseEntity<AwsErrorBody> handleAws(MockAwsException ex) {
        return ResponseEntity.status(ex.status()).body(new AwsErrorBody(ex.type(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AwsErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AwsErrorBody("SerializationException", "Request body could not be parsed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AwsErrorBody> handleUnexpected(Exception ex) {
        log.error("Mock AWS 내부 오류", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AwsErrorBody("InternalServiceErrorException", "An internal error has occurred."));
    }
}
