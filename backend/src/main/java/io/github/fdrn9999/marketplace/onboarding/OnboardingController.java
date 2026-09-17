package io.github.fdrn9999.marketplace.onboarding;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.OnboardingSession;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.onboarding.OnboardingService.RegistrationForm;
import io.github.fdrn9999.marketplace.onboarding.OnboardingService.RegistrationResult;
import io.github.fdrn9999.marketplace.subscription.SubscriptionService;
import io.github.fdrn9999.marketplace.subscription.SubscriptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
public class OnboardingController {

    public record OnboardingView(String onboardingId, String customerAWSAccountId, String licenseArn,
            String productCode, String productName, PricingModel pricingModel, Instant expiresAt,
            boolean alreadyRegistered) {
    }

    public record RegisterRequest(
            @NotBlank(message = "등록 세션 ID가 필요합니다") String onboardingId,
            @NotBlank(message = "회사명을 입력해 주세요") @Size(max = 100, message = "회사명은 100자 이하입니다") String companyName,
            @NotBlank(message = "담당자 이름을 입력해 주세요") @Size(max = 50, message = "담당자 이름은 50자 이하입니다") String contactPerson,
            @NotBlank(message = "연락처를 입력해 주세요")
            @Pattern(regexp = "^[0-9+\\-() ]{7,20}$", message = "연락처 형식이 올바르지 않습니다") String contactPhone,
            @NotBlank(message = "이메일을 입력해 주세요") @Email(message = "이메일 형식이 올바르지 않습니다") String contactEmail) {
    }

    public record RegisterResponse(String subscriberId, boolean alreadyRegistered, String entitlementSyncError,
            SubscriptionView subscription) {
    }

    private final OnboardingService onboarding;
    private final SubscriptionService subscriptions;
    private final SimulatedClock clock;

    public OnboardingController(OnboardingService onboarding, SubscriptionService subscriptions, SimulatedClock clock) {
        this.onboarding = onboarding;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /** 등록 화면 초기 정보: resolve된 AWS 계정과 구매 상품 */
    @GetMapping("/api/onboarding/{onboardingId}")
    public OnboardingView session(@PathVariable String onboardingId) {
        OnboardingSession session = onboarding.session(onboardingId);
        Product product = onboarding.product(session);
        return new OnboardingView(session.id(), session.customerAWSAccountId(), session.licenseArn(),
                product.productCode(), product.name(), product.pricingModel(), session.expiresAt(),
                onboarding.isRegistered(session.licenseArn()));
    }

    /** 등록 폼 제출 → 구독자 저장 → (계약형) Entitlement 동기화 */
    @PostMapping("/api/subscribers")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        RegistrationResult result = onboarding.register(new RegistrationForm(request.onboardingId(),
                request.companyName(), request.contactPerson(), request.contactPhone(), request.contactEmail()));
        String subscriberId = result.subscriber().getSubscriberId();
        return new RegisterResponse(subscriberId, result.alreadyRegistered(), result.entitlementSyncError(),
                SubscriptionView.of(subscriptions.load(subscriberId), clock.now()));
    }
}
