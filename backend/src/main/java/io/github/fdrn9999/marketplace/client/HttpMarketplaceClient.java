package io.github.fdrn9999.marketplace.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.fdrn9999.marketplace.awsapi.AwsErrorBody;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.Entitlement;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsRequest;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecord;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.config.HttpClientConfig;
import io.github.fdrn9999.marketplace.config.LocalServerUrl;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock AWS Marketplace API를 HTTP로 호출하는 클라이언트.
 * Throttling / 5xx / 네트워크 오류는 지수 백오프로 재시도하고, 그래도 실패하면 {@link MarketplaceApiException}을 던진다.
 */
@Component
public class HttpMarketplaceClient implements MarketplaceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMarketplaceClient.class);
    private static final long BASE_BACKOFF_MS = 100;

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final LocalServerUrl localServerUrl;
    private final AppProperties properties;

    public HttpMarketplaceClient(@Qualifier(HttpClientConfig.MARKETPLACE_CLIENT) RestClient restClient,
            JsonMapper jsonMapper, LocalServerUrl localServerUrl, AppProperties properties) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.localServerUrl = localServerUrl;
        this.properties = properties;
    }

    @Override
    public ResolveCustomerResult resolveCustomer(String registrationToken) {
        return withRetry("ResolveCustomer", () -> call("ResolveCustomer", "/mock-aws/metering/resolve-customer",
                new ResolveCustomerRequest(registrationToken), ResolveCustomerResult.class));
    }

    @Override
    public List<Entitlement> getEntitlements(String productCode, String licenseArn) {
        List<Entitlement> all = new ArrayList<>();
        String nextToken = null;
        do {
            GetEntitlementsRequest request = new GetEntitlementsRequest(productCode,
                    Map.of(EntitlementApi.FILTER_LICENSE_ARN, List.of(licenseArn)), nextToken, null);
            GetEntitlementsResult page = withRetry("GetEntitlements", () -> call("GetEntitlements",
                    "/mock-aws/entitlement/get-entitlements", request, GetEntitlementsResult.class));
            if (page.entitlements() != null) {
                all.addAll(page.entitlements());
            }
            nextToken = page.nextToken();
        } while (nextToken != null);
        return all;
    }

    @Override
    public BatchMeterUsageResult batchMeterUsage(List<UsageRecord> usageRecords) {
        BatchMeterUsageRequest request = new BatchMeterUsageRequest(null, usageRecords);
        return withRetry("BatchMeterUsage", () -> call("BatchMeterUsage", "/mock-aws/metering/batch-meter-usage",
                request, BatchMeterUsageResult.class));
    }

    private <T> T call(String api, String path, Object body, Class<T> responseType) {
        String url = localServerUrl.resolve(properties.mockAws().baseUrl()) + path;
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (ResourceAccessException e) {
            throw new MarketplaceApiException(api, MarketplaceApiException.NETWORK_ERROR, 0, e.getMessage(), e);
        }
        int status = response.getStatusCode().value();
        String raw = response.getBody();
        try {
            if (response.getStatusCode().is2xxSuccessful()) {
                return jsonMapper.readValue(raw, responseType);
            }
            AwsErrorBody error = raw == null || raw.isBlank() ? null : jsonMapper.readValue(raw, AwsErrorBody.class);
            String type = error != null && error.type() != null ? error.type() : "HttpError" + status;
            throw new MarketplaceApiException(api, type, status, error == null ? "" : error.message(), null);
        } catch (JacksonException e) {
            throw new MarketplaceApiException(api, "SerializationError", status, e.getOriginalMessage(), e);
        }
    }

    private <T> T withRetry(String api, Supplier<T> action) {
        int retries = Math.max(0, properties.metering().callRetries());
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (MarketplaceApiException e) {
                if (!e.isRetryable() || attempt >= retries) {
                    throw e;
                }
                long backoff = BASE_BACKOFF_MS * (1L << attempt);
                log.info("{} 재시도 {}/{} ({}ms 후): {}", api, attempt + 1, retries, backoff, e.awsErrorType());
                sleep(backoff);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketplaceApiException("retry", "Interrupted", 0, "재시도 대기 중 인터럽트", e);
        }
    }
}
