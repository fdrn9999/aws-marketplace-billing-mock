package io.github.fdrn9999.marketplace.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

/** 앱 컨트롤러에서 발생한 예외를 {@link ErrorResponse}로 변환한다. Mock AWS 컨트롤러는 별도 핸들러(AWS 오류 형식)를 쓴다. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.code().status().is5xxServerError()) {
            log.warn("{} {} -> {}: {}", request.getMethod(), request.getRequestURI(), ex.code(), ex.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.code().status());
        if (ex.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(ErrorResponse.of(ex.code(), ex.getMessage(), ex.details(), RequestIdFilter.currentId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), Map.of("fields", fields), request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        // 역직렬화 내부 메시지는 응답에 넣지 않고 요청 ID와 함께 로그에만 남긴다
        log.info("잘못된 요청 {} {} [{}]: {}", request.getMethod(), request.getRequestURI(),
                RequestIdFilter.currentId(request), rootMessage(ex));
        String message = ex instanceof MissingServletRequestParameterException missing
                ? "필수 파라미터가 없습니다: " + missing.getParameterName()
                : ex instanceof MethodArgumentTypeMismatchException mismatch
                        ? "파라미터 형식이 올바르지 않습니다: " + mismatch.getName()
                        : "요청 본문을 해석할 수 없습니다 (JSON 형식과 값의 타입을 확인해 주세요)";
        return build(ErrorCode.VALIDATION_ERROR, message, Map.of(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "필수 헤더가 없습니다: " + ex.getHeaderName(), Map.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "지원하지 않는 경로입니다: " + request.getMethod() + " " + request.getRequestURI(), Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), Map.of(), request);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details,
            HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, message, details, RequestIdFilter.currentId(request)));
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
