package io.github.fdrn9999.marketplace.awsapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * AWS Marketplace Metering Service의 요청/응답 형식 (가이드 p13, p16 + AWS SDK 모델).
 * JSON 필드는 AWS와 같은 PascalCase이고, 시각은 epoch 초(long)다.
 * Mock AWS 서버와 앱의 클라이언트가 이 계약을 함께 사용한다(실제 환경의 AWS SDK 모델 역할).
 */
public final class MeteringApi {

    private MeteringApi() {
    }

    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record ResolveCustomerRequest(String registrationToken) {
    }

    /** 신규 연동(2026-06 이후)에서는 CustomerIdentifier가 비어 있을 수 있다. CustomerAWSAccountId + LicenseArn을 사용한다. */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResolveCustomerResult(
            String customerIdentifier,
            String customerAWSAccountId,
            String productCode,
            String licenseArn) {
    }

    /**
     * LicenseArn 기반(신규) 연동에서는 요청 레벨 ProductCode를 넣지 않는다.
     * 가이드 p16 예시처럼 둘 다 넣은 요청도 Mock은 받아준다.
     */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchMeterUsageRequest(String productCode, List<UsageRecord> usageRecords) {
    }

    /**
     * @param timestamp 사용량이 발생한 시각(epoch 초, UTC). 24시간 이상 지난 값은 거부된다
     * @param quantity  보고할 수량 (0 허용)
     */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageRecord(
            Long timestamp,
            String customerIdentifier,
            String customerAWSAccountId,
            String dimension,
            Integer quantity,
            String licenseArn) {
    }

    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record BatchMeterUsageResult(List<UsageRecordResult> results, List<UsageRecord> unprocessedRecords) {
    }

    /** status: {@link #SUCCESS}, {@link #CUSTOMER_NOT_SUBSCRIBED}, {@link #DUPLICATE_RECORD} */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageRecordResult(String meteringRecordId, String status, UsageRecord usageRecord) {
    }

    public static final String SUCCESS = "Success";
    public static final String CUSTOMER_NOT_SUBSCRIBED = "CustomerNotSubscribed";
    public static final String DUPLICATE_RECORD = "DuplicateRecord";
}
