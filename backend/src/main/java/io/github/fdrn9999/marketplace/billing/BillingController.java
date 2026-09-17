package io.github.fdrn9999.marketplace.billing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.access.CustomerHeader;

@RestController
@RequestMapping("/api/me")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    /** 집계 기간의 차원별 사용량, 계약 수량 대비 사용률, 최근 12시간 추이 */
    @GetMapping("/usage")
    public BillingService.UsageSummary usage(
            @RequestHeader(value = CustomerHeader.NAME, required = false) String customerId) {
        return billing.usage(CustomerHeader.require(customerId));
    }

    /** 미터링 레코드 이력과 이번 달 예상 청구액 */
    @GetMapping("/billing")
    public BillingService.BillingSummary billing(
            @RequestHeader(value = CustomerHeader.NAME, required = false) String customerId) {
        return billing.billing(CustomerHeader.require(customerId));
    }
}
