package io.github.fdrn9999.marketplace.mockaws;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecord;

/**
 * Mock AWS Marketplace Metering Service.
 * 실제 AWS는 단일 엔드포인트에 {@code X-Amz-Target} 헤더로 작업을 구분하지만, 여기서는 읽기 쉽게 경로로 나눴다.
 */
@RestController
@RequestMapping("/mock-aws/metering")
public class MockMeteringController {

    private final MockMarketplaceService service;
    private final MockAwsCallLog callLog;
    private final MockAwsStore store;

    public MockMeteringController(MockMarketplaceService service, MockAwsCallLog callLog, MockAwsStore store) {
        this.service = service;
        this.callLog = callLog;
        this.store = store;
    }

    @PostMapping("/resolve-customer")
    public ResolveCustomerResult resolveCustomer(@RequestBody(required = false) ResolveCustomerRequest request) {
        String licenseArn = request == null ? null
                : store.token(request.registrationToken()).map(t -> t.licenseArn).orElse(null);
        return callLog.record("ResolveCustomer", licenseArn, request, () -> service.resolveCustomer(request));
    }

    @PostMapping("/batch-meter-usage")
    public BatchMeterUsageResult batchMeterUsage(@RequestBody(required = false) BatchMeterUsageRequest request) {
        return callLog.record("BatchMeterUsage", singleLicense(request), request, () -> service.batchMeterUsage(request));
    }

    /** 배치의 레코드가 모두 같은 라이선스면 그 값을, 아니면 null (호출 로그 필터용) */
    private static String singleLicense(BatchMeterUsageRequest request) {
        if (request == null || request.usageRecords() == null || request.usageRecords().isEmpty()) {
            return null;
        }
        String first = request.usageRecords().get(0) == null ? null : request.usageRecords().get(0).licenseArn();
        for (UsageRecord record : request.usageRecords()) {
            if (record == null || first == null || !first.equals(record.licenseArn())) {
                return null;
            }
        }
        return first;
    }
}
