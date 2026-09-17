package io.github.fdrn9999.marketplace.mockaws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.fdrn9999.marketplace.awsapi.MarketplaceEvent;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.config.HttpClientConfig;
import io.github.fdrn9999.marketplace.config.LocalServerUrl;

/**
 * EventBridge → SQS → 핸들러 경로를 대신해 앱의 이벤트 수신 API로 이벤트를 HTTP 전달한다.
 * [Mock 정책] 전달은 동기 1회이며 실패해도 재시도하지 않는다(결과만 반환).
 */
@Component
public class MarketplaceEventDelivery {

    public static final String SECRET_HEADER = "X-Marketplace-Event-Secret";
    private static final Logger log = LoggerFactory.getLogger(MarketplaceEventDelivery.class);

    private final RestClient restClient;
    private final LocalServerUrl localServerUrl;
    private final AppProperties properties;

    public MarketplaceEventDelivery(@Qualifier(HttpClientConfig.MARKETPLACE_CLIENT) RestClient restClient,
            LocalServerUrl localServerUrl, AppProperties properties) {
        this.restClient = restClient;
        this.localServerUrl = localServerUrl;
        this.properties = properties;
    }

    /** @return 전달 성공 여부 */
    public boolean deliver(MarketplaceEvent event) {
        // 앱과 Mock AWS가 같은 서버에서 실행되므로 이벤트 수신 API도 이 서버 자신이다
        String url = localServerUrl.resolve(null) + "/api/internal/marketplace-events";
        try {
            restClient.post()
                    .uri(url)
                    .header(SECRET_HEADER, properties.eventSecret())
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("Marketplace 이벤트 전달 실패: {} {} - {}", event.type(), event.licenseArn(), e.getMessage());
            return false;
        }
    }
}
