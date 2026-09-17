package io.github.fdrn9999.marketplace.client;

import java.util.List;

import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.Entitlement;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecord;

/**
 * 앱이 AWS Marketplace에 접근하는 유일한 통로.
 * 지금은 Mock AWS를 HTTP로 호출하는 {@link HttpMarketplaceClient}를 쓰고,
 * 실제 운영에서는 AWS SDK for Java v2(MarketplaceMeteringClient, MarketplaceEntitlementClient) 구현으로 교체한다.
 *
 * <p>실패 시 {@link MarketplaceApiException}을 던진다. 재시도 가능한 오류(Throttling, 5xx, 네트워크)는 구현체가 먼저 재시도한다.
 */
public interface MarketplaceClient {

    /** 등록 토큰 → 구매자 정보 (가이드 p13) */
    ResolveCustomerResult resolveCustomer(String registrationToken);

    /** 라이선스의 모든 Entitlement (가이드 p17). NextToken 페이지를 끝까지 읽는다 */
    List<Entitlement> getEntitlements(String productCode, String licenseArn);

    /**
     * 사용량 보고 (가이드 p16). 신규 연동 규칙에 따라 요청 레벨 ProductCode 없이 LicenseArn 기반으로 보낸다.
     * 호출자는 레코드를 25건 이하로 나눠서 전달해야 한다.
     */
    BatchMeterUsageResult batchMeterUsage(List<UsageRecord> usageRecords);
}
