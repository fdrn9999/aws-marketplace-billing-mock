package io.github.fdrn9999.marketplace.subscription;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.access.CustomerHeader;
import io.github.fdrn9999.marketplace.common.SimulatedClock;

@RestController
@RequestMapping("/api/me/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final SimulatedClock clock;

    public SubscriptionController(SubscriptionService subscriptions, SimulatedClock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /** 현재 구독 상태. 미등록 고객도 200 + NOT_SUBSCRIBED로 응답한다. */
    @GetMapping
    public SubscriptionView current(@RequestHeader(value = CustomerHeader.NAME, required = false) String customerId) {
        return SubscriptionView.of(subscriptions.load(CustomerHeader.require(customerId)), clock.now());
    }

    /** GetEntitlements로 즉시 재동기화 */
    @PostMapping("/refresh")
    public SubscriptionView refresh(@RequestHeader(value = CustomerHeader.NAME, required = false) String customerId) {
        return SubscriptionView.of(subscriptions.refresh(CustomerHeader.require(customerId)), clock.now());
    }
}
