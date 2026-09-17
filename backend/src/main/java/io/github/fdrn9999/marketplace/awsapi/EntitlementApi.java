package io.github.fdrn9999.marketplace.awsapi;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * AWS Marketplace Entitlement Service의 GetEntitlements 요청/응답 형식 (가이드 p17 + AWS SDK 모델).
 */
public final class EntitlementApi {

    private EntitlementApi() {
    }

    /**
     * @param filter 필터 키 → 값 목록. AWS SDK 키({@code LICENSE_ARN}, {@code CUSTOMER_AWS_ACCOUNT_ID},
     *               {@code CUSTOMER_IDENTIFIER}, {@code DIMENSION})를 사용한다. 가이드 표기({@code LicenseArn})도 Mock이 허용한다
     */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetEntitlementsRequest(
            String productCode,
            Map<String, List<String>> filter,
            String nextToken,
            Integer maxResults) {
    }

    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetEntitlementsResult(List<Entitlement> entitlements, String nextToken) {
    }

    /**
     * @param expirationDate 만료 일시(epoch 초)
     * @param value          계약 수량 등 ({@code IntegerValue})
     */
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entitlement(
            String customerIdentifier,
            String customerAWSAccountId,
            String dimension,
            Long expirationDate,
            String productCode,
            String licenseArn,
            EntitlementValue value) {
    }

    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntitlementValue(Integer integerValue, Double doubleValue, Boolean booleanValue, String stringValue) {

        public static EntitlementValue ofInteger(int value) {
            return new EntitlementValue(value, null, null, null);
        }
    }

    public static final String FILTER_LICENSE_ARN = "LICENSE_ARN";
    public static final String FILTER_CUSTOMER_AWS_ACCOUNT_ID = "CUSTOMER_AWS_ACCOUNT_ID";
    public static final String FILTER_CUSTOMER_IDENTIFIER = "CUSTOMER_IDENTIFIER";
    public static final String FILTER_DIMENSION = "DIMENSION";
}
