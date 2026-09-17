package io.github.fdrn9999.marketplace.usage;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.access.CustomerHeader;

/** 구독 권한이 있어야 쓸 수 있는 보호 기능 (예시: AI 분석 실행). */
@RestController
@RequestMapping("/api/features")
public class FeatureController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /** @param dataGb 처리할 데이터량(GB, 1~100). 비우면 1 */
    public record AnalysisRequest(Integer dataGb) {
    }

    private final UsageService usage;

    public FeatureController(UsageService usage) {
        this.usage = usage;
    }

    @PostMapping("/analysis/run")
    public UsageService.RunResult runAnalysis(
            @RequestHeader(value = CustomerHeader.NAME, required = false) String customerId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AnalysisRequest request) {
        int dataGb = request == null || request.dataGb() == null ? 1 : request.dataGb();
        return usage.runAnalysis(CustomerHeader.require(customerId), dataGb, idempotencyKey);
    }
}
