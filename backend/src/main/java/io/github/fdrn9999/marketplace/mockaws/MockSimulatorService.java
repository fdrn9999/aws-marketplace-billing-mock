package io.github.fdrn9999.marketplace.mockaws;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.fdrn9999.marketplace.awsapi.MarketplaceEvent;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.config.AppProperties;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.License;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.LicenseState;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.Listing;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredEntitlement;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredToken;

/**
 * AWS Marketplace 화면/백오피스에서 일어나는 일을 흉내 내는 시뮬레이터 (데모 전용).
 * 구매(라이선스·계약·등록 토큰 생성)와 구독 이벤트 발행(시작/갱신/해지)을 처리한다.
 */
@Service
public class MockSimulatorService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param customerAWSAccountId      구매자 AWS 계정(12자리). 비우면 임의 생성
     * @param deliverSubscriptionEvent  구매 직후 "구독 시작" 이벤트를 앱에 전달할지 (기본 true)
     */
    public record PurchaseRequest(String productCode, String customerAWSAccountId, Boolean freeTrial,
            Boolean deliverSubscriptionEvent) {
    }

    /** @param fulfillmentUrl 구매자 브라우저가 등록 토큰을 form POST할 판매자 주소 */
    public record PurchaseResult(String registrationToken, String fulfillmentUrl, String tokenFieldName,
            String licenseArn, String customerAWSAccountId, String productCode, Instant tokenExpiresAt,
            boolean subscriptionEventDelivered) {
    }

    /**
     * @param renewDays ENTITLEMENT_UPDATED일 때 새 만료일(현재 시각 + N일). 기본 365
     * @param quantity  ENTITLEMENT_UPDATED일 때 새 계약 수량. 비우면 기존 값 유지
     */
    public record EventRequest(MarketplaceEvent.Type type, String licenseArn, Integer renewDays, Integer quantity) {
    }

    public record EventResult(MarketplaceEvent event, boolean delivered) {
    }

    private final MockAwsStore store;
    private final MarketplaceEventDelivery delivery;
    private final SimulatedClock clock;
    private final AppProperties properties;

    public MockSimulatorService(MockAwsStore store, MarketplaceEventDelivery delivery, SimulatedClock clock,
            AppProperties properties) {
        this.store = store;
        this.delivery = delivery;
        this.clock = clock;
        this.properties = properties;
    }

    public PurchaseResult purchase(PurchaseRequest request) {
        if (request == null || !StringUtils.hasText(request.productCode())) {
            throw MockAwsException.clientError("ValidationException", "productCode is required.");
        }
        Listing listing = store.listing(request.productCode()).orElseThrow(
                () -> MockAwsException.clientError("ValidationException", "Unknown productCode: " + request.productCode()));
        String accountId = request.customerAWSAccountId();
        if (!StringUtils.hasText(accountId)) {
            accountId = randomAccountId();
        } else if (!accountId.matches("\\d{12}")) {
            throw MockAwsException.clientError("ValidationException", "customerAWSAccountId must be 12 digits.");
        }

        Instant now = clock.now();
        String suffix = Ids.next("l").substring(2);
        License license = new License("arn:aws:license-manager::" + accountId + ":license:l-" + suffix,
                accountId, "cust-" + suffix, listing.productCode(), Boolean.TRUE.equals(request.freeTrial()),
                LicenseState.ACTIVE);
        store.addLicense(license);
        for (MockAwsStore.ContractDimension contract : listing.contractDimensions()) {
            store.addEntitlement(new StoredEntitlement(license.licenseArn, contract.dimension(), contract.quantity(),
                    now.plus(Duration.ofDays(contract.durationDays()))));
        }

        StoredToken token = new StoredToken(randomToken(), license.licenseArn,
                now.plus(properties.mockAws().registrationTokenTtl()), false);
        store.addToken(token);

        boolean delivered = false;
        if (!Boolean.FALSE.equals(request.deliverSubscriptionEvent())) {
            delivered = delivery.deliver(toEvent(MarketplaceEvent.Type.SUBSCRIPTION_STARTED, license));
        }
        return new PurchaseResult(token.token, "/marketplace/fulfillment", "x-amzn-marketplace-token",
                license.licenseArn, accountId, listing.productCode(), token.expiresAt, delivered);
    }

    /** 이미 구매한 라이선스에 대해 "계정 설정"을 다시 누른 경우: AWS는 새 등록 토큰을 발급한다. */
    public PurchaseResult issueToken(String licenseArn) {
        License license = store.license(licenseArn).orElseThrow(
                () -> MockAwsException.clientError("ResourceNotFoundException", "Unknown licenseArn."));
        StoredToken token = new StoredToken(randomToken(), license.licenseArn,
                clock.now().plus(properties.mockAws().registrationTokenTtl()), false);
        store.addToken(token);
        return new PurchaseResult(token.token, "/marketplace/fulfillment", "x-amzn-marketplace-token",
                license.licenseArn, license.customerAWSAccountId, license.productCode, token.expiresAt, false);
    }

    public EventResult publish(EventRequest request) {
        if (request == null || request.type() == null || !StringUtils.hasText(request.licenseArn())) {
            throw MockAwsException.clientError("ValidationException", "type and licenseArn are required.");
        }
        License license = store.license(request.licenseArn()).orElseThrow(
                () -> MockAwsException.clientError("ResourceNotFoundException", "Unknown licenseArn."));
        Instant now = clock.now();
        switch (request.type()) {
            case SUBSCRIPTION_STARTED -> license.state = LicenseState.ACTIVE;
            case SUBSCRIPTION_CANCELLED -> license.state = LicenseState.CANCELLED;
            case ENTITLEMENT_UPDATED -> {
                int days = request.renewDays() == null ? 365 : request.renewDays();
                if (days < 1) {
                    throw MockAwsException.clientError("ValidationException", "renewDays must be positive.");
                }
                List<StoredEntitlement> owned = store.entitlements().stream()
                        .filter(e -> e.licenseArn.equals(license.licenseArn))
                        .toList();
                for (StoredEntitlement entitlement : owned) {
                    entitlement.expirationDate = now.plus(Duration.ofDays(days));
                    if (request.quantity() != null) {
                        entitlement.integerValue = request.quantity();
                    }
                }
                license.state = LicenseState.ACTIVE;
            }
            default -> throw new IllegalStateException("지원하지 않는 이벤트: " + request.type());
        }
        MarketplaceEvent event = toEvent(request.type(), license);
        return new EventResult(event, delivery.deliver(event));
    }

    private MarketplaceEvent toEvent(MarketplaceEvent.Type type, License license) {
        return new MarketplaceEvent(type, license.licenseArn, license.customerAWSAccountId, license.productCode,
                license.freeTrial, clock.now());
    }

    private static String randomAccountId() {
        StringBuilder sb = new StringBuilder("9");
        for (int i = 0; i < 11; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
