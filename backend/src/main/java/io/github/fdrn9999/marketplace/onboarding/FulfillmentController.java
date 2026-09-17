package io.github.fdrn9999.marketplace.onboarding;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.domain.OnboardingSession;

/**
 * Fulfillment URL (가이드 p12, p19~20).
 * 구매자가 AWS Marketplace에서 "계정 설정"을 누르면 브라우저가 등록 토큰을 이 주소로 form POST한다.
 * 토큰을 즉시 조회한 뒤 등록 화면으로 302 리디렉션한다. 실패하면 오류 코드를 붙여 같은 화면으로 보낸다.
 */
@RestController
@RequestMapping("/marketplace/fulfillment")
public class FulfillmentController {

    public static final String TOKEN_FIELD = "x-amzn-marketplace-token";

    private final OnboardingService onboarding;
    private final AppProperties properties;

    public FulfillmentController(OnboardingService onboarding, AppProperties properties) {
        this.onboarding = onboarding;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Void> fulfill(
            @RequestParam(value = TOKEN_FIELD, required = false) String formToken,
            @RequestHeader(value = TOKEN_FIELD, required = false) String headerToken) {
        String token = StringUtils.hasText(formToken) ? formToken : headerToken;
        String location;
        try {
            OnboardingSession session = onboarding.redeem(token);
            location = properties.webBaseUrl() + "/register?onboarding=" + encode(session.id());
        } catch (ApiException e) {
            location = properties.webBaseUrl() + "/register?error=" + encode(e.code().name());
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
