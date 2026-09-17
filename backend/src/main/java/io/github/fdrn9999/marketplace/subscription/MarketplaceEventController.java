package io.github.fdrn9999.marketplace.subscription;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.awsapi.MarketplaceEvent;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.mockaws.MarketplaceEventDelivery;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Marketplace 이벤트 수신 API (SQS 소비자 역할).
 * 외부에서 임의 호출하지 못하도록 공유 비밀값 헤더를 확인한다. 실제 환경에서는 SQS 접근 권한이 이 역할을 한다.
 * 인증 전에 본문을 해석하지 않도록 본문은 문자열로 받아 비밀값 확인 후 파싱한다.
 */
@RestController
@RequestMapping("/api/internal/marketplace-events")
public class MarketplaceEventController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEventController.class);

    private final MarketplaceEventHandler handler;
    private final AppProperties properties;
    private final JsonMapper jsonMapper;

    public MarketplaceEventController(MarketplaceEventHandler handler, AppProperties properties,
            JsonMapper jsonMapper) {
        this.handler = handler;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public MarketplaceEventHandler.HandleResult receive(
            @RequestHeader(value = MarketplaceEventDelivery.SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) String body) {
        if (secret == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                properties.eventSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "이벤트 비밀값이 올바르지 않습니다");
        }
        MarketplaceEvent event;
        try {
            event = body == null ? null : jsonMapper.readValue(body, MarketplaceEvent.class);
        } catch (JacksonException e) {
            log.info("이벤트 본문 해석 실패: {}", e.getOriginalMessage());
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "이벤트 본문을 해석할 수 없습니다");
        }
        return handler.handle(event);
    }
}
