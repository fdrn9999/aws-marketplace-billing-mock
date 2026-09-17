package io.github.fdrn9999.marketplace.mockaws;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.awsapi.EntitlementApi;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsRequest;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsResult;

/** Mock AWS Marketplace Entitlement Service. */
@RestController
@RequestMapping("/mock-aws/entitlement")
public class MockEntitlementController {

    private final MockMarketplaceService service;
    private final MockAwsCallLog callLog;

    public MockEntitlementController(MockMarketplaceService service, MockAwsCallLog callLog) {
        this.service = service;
        this.callLog = callLog;
    }

    @PostMapping("/get-entitlements")
    public GetEntitlementsResult getEntitlements(@RequestBody(required = false) GetEntitlementsRequest request) {
        return callLog.record("GetEntitlements", licenseFilter(request), request, () -> service.getEntitlements(request));
    }

    private static String licenseFilter(GetEntitlementsRequest request) {
        if (request == null || request.filter() == null) {
            return null;
        }
        List<String> values = request.filter().get(EntitlementApi.FILTER_LICENSE_ARN);
        if (values == null) {
            values = request.filter().get("LicenseArn");
        }
        return values != null && values.size() == 1 ? values.get(0) : null;
    }
}
