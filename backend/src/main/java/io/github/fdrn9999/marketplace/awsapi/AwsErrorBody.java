package io.github.fdrn9999.marketplace.awsapi;

import com.fasterxml.jackson.annotation.JsonProperty;

/** AWS JSON 프로토콜의 오류 본문: {@code {"__type": "InvalidTokenException", "message": "..."}} */
public record AwsErrorBody(@JsonProperty("__type") String type, @JsonProperty("message") String message) {
}
