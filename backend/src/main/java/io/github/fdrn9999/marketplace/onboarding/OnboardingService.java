package io.github.fdrn9999.marketplace.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
import io.github.fdrn9999.marketplace.client.MarketplaceApiException;
import io.github.fdrn9999.marketplace.client.MarketplaceClient;
import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.KeyedLocks;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.OnboardingSession;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.store.OnboardingSessionRepository;
import io.github.fdrn9999.marketplace.store.ProductRepository;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;
import io.github.fdrn9999.marketplace.subscription.EntitlementService;

/**
 * 가이드의 기술적 통합 1단계 "통합 온보딩": ① Fulfillment URL ② ResolveCustomer.
 *
 * <p>SDK 문서 권고에 따라 등록 토큰은 Fulfillment URL에서 받자마자 조회(redeem)한다.
 * 이후 등록 화면은 토큰 대신 짧은 수명의 온보딩 세션 ID를 사용한다.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    /** [Mock 정책] 등록 폼 작성에 주는 시간 */
    static final Duration SESSION_TTL = Duration.ofMinutes(30);

    public record RegistrationForm(String onboardingId, String companyName, String contactPerson, String contactPhone,
            String contactEmail) {
    }

    public record RegistrationResult(Subscriber subscriber, boolean alreadyRegistered, String entitlementSyncError) {
    }

    private final MarketplaceClient client;
    private final ProductRepository products;
    private final SubscriberRepository subscribers;
    private final OnboardingSessionRepository sessions;
    private final EntitlementService entitlements;
    private final KeyedLocks locks;
    private final SimulatedClock clock;

    public OnboardingService(MarketplaceClient client, ProductRepository products, SubscriberRepository subscribers,
            OnboardingSessionRepository sessions, EntitlementService entitlements, KeyedLocks locks,
            SimulatedClock clock) {
        this.client = client;
        this.products = products;
        this.subscribers = subscribers;
        this.sessions = sessions;
        this.entitlements = entitlements;
        this.locks = locks;
        this.clock = clock;
    }

    /** Fulfillment URL로 받은 등록 토큰을 즉시 조회하고 온보딩 세션을 만든다. */
    public OnboardingSession redeem(String registrationToken) {
        if (!StringUtils.hasText(registrationToken)) {
            throw new ApiException(ErrorCode.INVALID_REGISTRATION_TOKEN, "등록 토큰이 전달되지 않았습니다");
        }
        ResolveCustomerResult customer;
        try {
            customer = client.resolveCustomer(registrationToken.trim());
        } catch (MarketplaceApiException e) {
            throw toApiException(e);
        }
        if (products.findByCode(customer.productCode()).isEmpty()) {
            throw new ApiException(ErrorCode.UNKNOWN_PRODUCT, ErrorCode.UNKNOWN_PRODUCT.defaultMessage(),
                    Map.of("productCode", String.valueOf(customer.productCode())));
        }
        Instant now = clock.now();
        OnboardingSession session = new OnboardingSession(Ids.next("onb"), customer.licenseArn(),
                customer.customerAWSAccountId(), customer.customerIdentifier(), customer.productCode(), now,
                now.plus(SESSION_TTL), false);
        log.info("등록 토큰 조회 완료: {} → {}", customer.licenseArn(), session.id());
        return sessions.save(session);
    }

    /** 등록 화면에 보여줄 세션 정보. 만료되었거나 완료된 세션은 오류. */
    public OnboardingSession session(String onboardingId) {
        OnboardingSession session = sessions.findById(onboardingId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "등록 세션을 찾을 수 없습니다"));
        if (!session.isUsableAt(clock.now())) {
            throw new ApiException(ErrorCode.ONBOARDING_SESSION_EXPIRED);
        }
        return session;
    }

    public Product product(OnboardingSession session) {
        return products.findByCode(session.productCode()).orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PRODUCT));
    }

    public boolean isRegistered(String licenseArn) {
        return subscribers.findByLicenseArn(licenseArn).map(Subscriber::isSuccessfullyRegistered).orElse(false);
    }

    /**
     * 등록 폼 제출 (QuickStart RegisterNewMarketplaceCustomer).
     * 구독 이벤트가 먼저 와서 만들어 둔 미등록 레코드가 있으면 그 레코드를 완성한다. 재등록은 연락처만 갱신한다.
     */
    public RegistrationResult register(RegistrationForm form) {
        OnboardingSession session = session(form.onboardingId());
        Product product = product(session);

        RegistrationResult result = locks.withLock(session.licenseArn(), () -> {
            OnboardingSession current = session(form.onboardingId());
            Instant now = clock.now();
            Subscriber existing = subscribers.findByLicenseArn(current.licenseArn()).orElse(null);
            boolean alreadyRegistered = existing != null && existing.isSuccessfullyRegistered();
            Subscriber s = existing != null ? existing : new Subscriber();
            if (existing == null) {
                s.setSubscriberId(Ids.next("sub"));
                s.setLicenseArn(current.licenseArn());
                s.setCreatedAt(now);
            }
            s.setCustomerAWSAccountId(current.customerAWSAccountId());
            s.setCustomerIdentifier(current.customerIdentifier());
            s.setProductCode(current.productCode());
            s.setCompanyName(form.companyName().trim());
            s.setContactPerson(form.contactPerson().trim());
            s.setContactPhone(form.contactPhone().trim());
            s.setContactEmail(form.contactEmail().trim());
            s.setSuccessfullyRegistered(true);
            s.setUpdatedAt(now);
            subscribers.save(s);
            sessions.save(current.markCompleted());
            return new RegistrationResult(s, alreadyRegistered, null);
        });

        if (product.pricingModel().usesEntitlements()) {
            try {
                entitlements.sync(result.subscriber(), product);
            } catch (MarketplaceApiException e) {
                // 등록은 완료하고, 계약 정보는 다음 조회 때 다시 확인한다
                return new RegistrationResult(result.subscriber(), result.alreadyRegistered(), e.awsErrorType());
            }
        }
        return result;
    }

    static ApiException toApiException(MarketplaceApiException e) {
        switch (e.awsErrorType()) {
            case "InvalidTokenException":
                return new ApiException(ErrorCode.INVALID_REGISTRATION_TOKEN);
            case "ExpiredTokenException":
                return new ApiException(ErrorCode.EXPIRED_REGISTRATION_TOKEN);
            default:
                return new ApiException(ErrorCode.MARKETPLACE_UNAVAILABLE,
                        ErrorCode.MARKETPLACE_UNAVAILABLE.defaultMessage() + " (" + e.awsErrorType() + ")",
                        Map.of("awsErrorType", e.awsErrorType()), 30L);
        }
    }
}
