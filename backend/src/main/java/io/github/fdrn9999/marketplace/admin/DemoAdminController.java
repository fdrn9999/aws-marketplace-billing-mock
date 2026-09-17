package io.github.fdrn9999.marketplace.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.common.ErrorCode;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.StatusReason;
import io.github.fdrn9999.marketplace.domain.Subscriber;
import io.github.fdrn9999.marketplace.domain.SubscriptionStatus;
import io.github.fdrn9999.marketplace.metering.MeteringJob;
import io.github.fdrn9999.marketplace.store.DemoDataService;
import io.github.fdrn9999.marketplace.store.SubscriberRepository;
import io.github.fdrn9999.marketplace.subscription.SubscriptionContext;
import io.github.fdrn9999.marketplace.subscription.SubscriptionService;

/**
 * 데모 전용 API: 고객 전환 목록, 시뮬레이션 시계, 미터링 수동 실행, 데이터 초기화.
 * {@code app.demo-mode=false}이면 등록되지 않는다.
 */
@RestController
@ConditionalOnProperty(name = "app.demo-mode", havingValue = "true", matchIfMissing = true)
public class DemoAdminController {

    /** 로그인하지 않은(구독자 레코드가 없는) 사용자를 시연하기 위한 가상 고객 ID */
    public static final String GUEST_ID = "guest";
    private static final Pattern SHORT_DURATION = Pattern.compile("^(\\d+)([dhm])$");

    public record CustomerItem(String customerId, String companyName, String productName, PricingModel pricingModel,
            SubscriptionStatus status, StatusReason reason) {
    }

    public record ClockView(Instant now, Instant currentHourStart, long offsetSeconds) {
    }

    /** @param advance 이동할 시간 ("1h", "1d", "31d" 또는 ISO-8601 "PT90M") */
    public record ClockRequest(String advance) {
    }

    private final SubscriberRepository subscribers;
    private final SubscriptionService subscriptions;
    private final MeteringJob meteringJob;
    private final DemoDataService demoData;
    private final SimulatedClock clock;

    public DemoAdminController(SubscriberRepository subscribers, SubscriptionService subscriptions,
            MeteringJob meteringJob, DemoDataService demoData, SimulatedClock clock) {
        this.subscribers = subscribers;
        this.subscriptions = subscriptions;
        this.meteringJob = meteringJob;
        this.demoData = demoData;
        this.clock = clock;
    }

    /** 등록된 구독자 + 미등록 가상 고객 목록 (대시보드 고객 전환용) */
    @GetMapping("/api/customers")
    public List<CustomerItem> customers() {
        List<CustomerItem> items = new ArrayList<>();
        for (Subscriber s : subscribers.findAll()) {
            if (!s.isSuccessfullyRegistered()) {
                continue;
            }
            SubscriptionContext context = subscriptions.load(s.getSubscriberId());
            items.add(new CustomerItem(s.getSubscriberId(), s.getCompanyName(), context.product().name(),
                    context.product().pricingModel(), context.evaluation().status(), context.evaluation().reason()));
        }
        items.add(new CustomerItem(GUEST_ID, "미등록 사용자", null, null, SubscriptionStatus.NOT_SUBSCRIBED,
                StatusReason.NOT_REGISTERED));
        return items;
    }

    @GetMapping("/api/admin/clock")
    public ClockView clock() {
        return new ClockView(clock.now(), clock.currentHourStart(), clock.offset().getSeconds());
    }

    @PostMapping("/api/admin/clock")
    public ClockView advance(@RequestBody(required = false) ClockRequest request) {
        if (request == null || request.advance() == null) {
            throw new ApiException(ErrorCode.INVALID_CLOCK_OPERATION, "advance 값이 필요합니다 (예: 1h, 1d, 31d)");
        }
        clock.advance(parse(request.advance().trim()));
        return clock();
    }

    @PostMapping("/api/admin/metering/run")
    public MeteringJob.RunSummary runMetering() {
        return meteringJob.run();
    }

    /** 시계와 모든 데이터를 시드 상태로 되돌린다 (Mock AWS 포함) */
    @PostMapping("/api/admin/reset")
    public Map<String, Object> reset() {
        demoData.reset();
        return Map.of("reset", true, "now", clock.now());
    }

    /** 한 번에 이동할 수 있는 최대 시간 */
    static final Duration MAX_ADVANCE = Duration.ofDays(400);

    static Duration parse(String value) {
        Duration duration;
        Matcher m = SHORT_DURATION.matcher(value);
        try {
            if (m.matches()) {
                long amount = Long.parseLong(m.group(1));
                duration = switch (m.group(2)) {
                    case "d" -> Duration.ofDays(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> Duration.ofMinutes(amount);
                };
            } else {
                duration = Duration.parse(value);
            }
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INVALID_CLOCK_OPERATION, "시간 형식이 올바르지 않습니다: " + value);
        }
        if (duration.compareTo(MAX_ADVANCE) > 0) {
            throw new ApiException(ErrorCode.INVALID_CLOCK_OPERATION, "한 번에 최대 400일까지 이동할 수 있습니다");
        }
        return duration;
    }
}
