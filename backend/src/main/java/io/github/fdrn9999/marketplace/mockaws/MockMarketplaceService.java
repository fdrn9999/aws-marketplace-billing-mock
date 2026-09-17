package io.github.fdrn9999.marketplace.mockaws;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.fdrn9999.marketplace.awsapi.EntitlementApi;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.Entitlement;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.EntitlementValue;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsRequest;
import io.github.fdrn9999.marketplace.awsapi.EntitlementApi.GetEntitlementsResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.BatchMeterUsageResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerRequest;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.ResolveCustomerResult;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecord;
import io.github.fdrn9999.marketplace.awsapi.MeteringApi.UsageRecordResult;
import io.github.fdrn9999.marketplace.common.Ids;
import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.mockaws.MockAwsFaults.Api;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.License;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.LicenseState;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.LedgerEntry;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredEntitlement;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredToken;

/**
 * Mock AWS Marketplace API 3종의 동작.
 * [AWS] 표시는 AWS SDK 문서로 확인한 동작이고, [Mock 정책] 표시는 과제용으로 단순화한 규칙이다.
 */
@Service
public class MockMarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MockMarketplaceService.class);

    /** [AWS] BatchMeterUsage 요청당 최대 레코드 수 */
    static final int MAX_USAGE_RECORDS = 25;
    /** [AWS] 이벤트 발생 후 24시간 이상 지난 레코드는 거부 */
    static final Duration MAX_RECORD_AGE = Duration.ofHours(24);
    /** [Mock 정책] 미래 시각 허용 오차 */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    /** [AWS] GetEntitlements MaxResults 상한 */
    static final int MAX_ENTITLEMENT_RESULTS = 25;

    /** 가이드 표기(PascalCase) → SDK 필터 키 */
    private static final Map<String, String> FILTER_ALIASES = Map.of(
            "LicenseArn", EntitlementApi.FILTER_LICENSE_ARN,
            "CustomerAWSAccountId", EntitlementApi.FILTER_CUSTOMER_AWS_ACCOUNT_ID,
            "CustomerIdentifier", EntitlementApi.FILTER_CUSTOMER_IDENTIFIER,
            "Dimension", EntitlementApi.FILTER_DIMENSION);
    private static final Set<String> FILTER_KEYS = Set.of(
            EntitlementApi.FILTER_LICENSE_ARN, EntitlementApi.FILTER_CUSTOMER_AWS_ACCOUNT_ID,
            EntitlementApi.FILTER_CUSTOMER_IDENTIFIER, EntitlementApi.FILTER_DIMENSION);

    private final MockAwsStore store;
    private final MockAwsFaults faults;
    private final SimulatedClock clock;

    public MockMarketplaceService(MockAwsStore store, MockAwsFaults faults, SimulatedClock clock) {
        this.store = store;
        this.faults = faults;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ ResolveCustomer

    /**
     * 등록 토큰으로 구매자 정보를 조회한다 (가이드 p13).
     * [AWS] 만료되었거나 이미 제출된 토큰은 ExpiredTokenException. 토큰은 1회용으로 처리한다.
     */
    public ResolveCustomerResult resolveCustomer(ResolveCustomerRequest request) {
        faults.checkCall(Api.RESOLVE_CUSTOMER);
        String tokenValue = request == null ? null : request.registrationToken();
        if (!StringUtils.hasText(tokenValue)) {
            throw MockAwsException.clientError("InvalidTokenException", "Registration token is invalid.");
        }
        StoredToken token = store.token(tokenValue)
                .orElseThrow(() -> MockAwsException.clientError("InvalidTokenException", "Registration token is invalid."));
        if (!clock.now().isBefore(token.expiresAt)) {
            throw MockAwsException.clientError("ExpiredTokenException", "The submitted registration token has expired.");
        }
        if (!store.redeem(token)) {
            throw MockAwsException.clientError("ExpiredTokenException",
                    "The submitted registration token has expired (it was already submitted).");
        }
        License license = store.license(token.licenseArn)
                .orElseThrow(() -> MockAwsException.clientError("InvalidTokenException", "Registration token is invalid."));
        return new ResolveCustomerResult(license.customerIdentifier, license.customerAWSAccountId,
                license.productCode, license.licenseArn);
    }

    // ------------------------------------------------------------------ GetEntitlements

    /**
     * 라이선스의 계약 정보를 조회한다 (가이드 p17).
     * [AWS] ProductCode 필수, 필터 키는 LICENSE_ARN 등, NextToken/MaxResults 페이징.
     */
    public GetEntitlementsResult getEntitlements(GetEntitlementsRequest request) {
        faults.checkCall(Api.GET_ENTITLEMENTS);
        if (request == null || !StringUtils.hasText(request.productCode())) {
            throw MockAwsException.clientError("InvalidParameterException", "ProductCode is required.");
        }
        if (store.listing(request.productCode()).isEmpty()) {
            throw MockAwsException.clientError("InvalidParameterException", "Unknown ProductCode: " + request.productCode());
        }
        int maxResults = request.maxResults() == null ? MAX_ENTITLEMENT_RESULTS : request.maxResults();
        if (maxResults < 1 || maxResults > MAX_ENTITLEMENT_RESULTS) {
            throw MockAwsException.clientError("InvalidParameterException", "MaxResults must be between 1 and 25.");
        }
        Predicate<Entitlement> filter = buildFilter(request.filter());

        List<Entitlement> matches = new ArrayList<>();
        for (StoredEntitlement stored : store.entitlements()) {
            License license = store.license(stored.licenseArn).orElse(null);
            if (license == null || !license.productCode.equals(request.productCode())) {
                continue;
            }
            Entitlement entitlement = new Entitlement(license.customerIdentifier, license.customerAWSAccountId,
                    stored.dimension, stored.expirationDate.getEpochSecond(), license.productCode,
                    license.licenseArn, EntitlementValue.ofInteger(stored.integerValue));
            if (filter.test(entitlement)) {
                matches.add(entitlement);
            }
        }

        int offset = decodeNextToken(request.nextToken());
        int end = Math.min(matches.size(), offset + maxResults);
        String nextToken = end < matches.size() ? encodeNextToken(end) : null;
        return new GetEntitlementsResult(offset >= matches.size() ? List.of() : matches.subList(offset, end), nextToken);
    }

    private Predicate<Entitlement> buildFilter(Map<String, List<String>> rawFilter) {
        if (rawFilter == null || rawFilter.isEmpty()) {
            return e -> true;
        }
        Map<String, Set<String>> filter = new LinkedHashMap<>();
        rawFilter.forEach((key, values) -> {
            String normalized = FILTER_ALIASES.getOrDefault(key, key);
            if (!FILTER_KEYS.contains(normalized)) {
                throw MockAwsException.clientError("InvalidParameterException", "Unsupported filter key: " + key);
            }
            filter.computeIfAbsent(normalized, k -> new HashSet<>()).addAll(values == null ? List.of() : values);
        });
        // 키끼리는 AND, 같은 키의 값끼리는 OR
        return e -> matches(filter, EntitlementApi.FILTER_LICENSE_ARN, e.licenseArn())
                && matches(filter, EntitlementApi.FILTER_CUSTOMER_AWS_ACCOUNT_ID, e.customerAWSAccountId())
                && matches(filter, EntitlementApi.FILTER_CUSTOMER_IDENTIFIER, e.customerIdentifier())
                && matches(filter, EntitlementApi.FILTER_DIMENSION, e.dimension());
    }

    private static boolean matches(Map<String, Set<String>> filter, String key, String value) {
        Set<String> allowed = filter.get(key);
        return allowed == null || allowed.contains(value);
    }

    private static String encodeNextToken(int offset) {
        return Base64.getUrlEncoder().encodeToString(("offset:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeNextToken(String token) {
        if (!StringUtils.hasText(token)) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            return Integer.parseInt(decoded.substring("offset:".length()));
        } catch (RuntimeException e) {
            throw MockAwsException.clientError("InvalidParameterException", "Invalid NextToken.");
        }
    }

    // ------------------------------------------------------------------ BatchMeterUsage

    /**
     * 시간 단위 사용량을 보고받는다 (가이드 p16).
     * <ul>
     *   <li>[AWS] 요청당 최대 25건, 24시간 이상 지난 Timestamp는 요청 전체가 TimestampOutOfBoundsException</li>
     *   <li>[AWS] 레코드별 Status: Success / CustomerNotSubscribed / DuplicateRecord(같은 고객·차원·시간에 다른 수량)</li>
     *   <li>[AWS] 같은 레코드 재전송은 멱등 → 같은 MeteringRecordId로 Success</li>
     *   <li>[Mock 정책] "같은 시간"은 Timestamp를 시(hour) 단위로 내림해서 비교</li>
     *   <li>[Mock 정책] UnprocessedRecords는 장애 주입(UNPROCESSED)이 설정된 경우에만 발생</li>
     * </ul>
     */
    public BatchMeterUsageResult batchMeterUsage(BatchMeterUsageRequest request) {
        faults.checkCall(Api.BATCH_METER_USAGE);
        List<UsageRecord> records = request == null ? null : request.usageRecords();
        if (records == null || records.isEmpty()) {
            throw MockAwsException.clientError("ValidationException", "UsageRecords must contain at least 1 record.");
        }
        if (records.size() > MAX_USAGE_RECORDS) {
            // [Mock 정책] 실제 AWS의 예외 이름은 SDK 모델에 명시되어 있지 않아 일반적인 ValidationException을 사용
            throw MockAwsException.clientError("ValidationException",
                    "UsageRecords must contain at most " + MAX_USAGE_RECORDS + " records.");
        }

        Instant now = clock.now();
        List<License> licenses = new ArrayList<>(records.size());
        for (UsageRecord record : records) {
            licenses.add(validateRecord(request.productCode(), record, now));
        }
        if (StringUtils.hasText(request.productCode()) && records.stream().anyMatch(r -> r.licenseArn() != null)) {
            log.info("[Mock AWS] ProductCode와 LicenseArn이 함께 전송됨: 신규 연동에서는 ProductCode를 생략해야 합니다");
        }

        List<UsageRecordResult> results = new ArrayList<>();
        List<UsageRecord> unprocessed = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            UsageRecord record = records.get(i);
            License license = licenses.get(i);
            if (faults.takeUnprocessed()) {
                unprocessed.add(record);
                continue;
            }
            if (license.state != LicenseState.ACTIVE) {
                results.add(new UsageRecordResult(null, MeteringApi.CUSTOMER_NOT_SUBSCRIBED, record));
                continue;
            }
            Instant hour = Instant.ofEpochSecond(record.timestamp()).truncatedTo(ChronoUnit.HOURS);
            String dedupeKey = license.licenseArn + "|" + record.dimension() + "|" + hour.getEpochSecond();
            LedgerEntry candidate = new LedgerEntry(Ids.next("mrec"), record.quantity(), now);
            LedgerEntry stored = store.recordIfAbsent(dedupeKey, candidate);
            if (stored == candidate || stored.quantity() == record.quantity()) {
                results.add(new UsageRecordResult(stored.meteringRecordId(), MeteringApi.SUCCESS, record));
            } else {
                results.add(new UsageRecordResult(null, MeteringApi.DUPLICATE_RECORD, record));
            }
        }
        return new BatchMeterUsageResult(results, unprocessed);
    }

    /** 요청 단위 예외에 해당하는 검증. 하나라도 걸리면 배치 전체가 실패한다(AWS와 동일). */
    private License validateRecord(String requestProductCode, UsageRecord record, Instant now) {
        if (record == null || record.timestamp() == null || !StringUtils.hasText(record.dimension())) {
            throw MockAwsException.clientError("ValidationException", "Timestamp and Dimension are required.");
        }
        if (record.quantity() == null || record.quantity() < 0) {
            throw MockAwsException.clientError("ValidationException", "Quantity must be a non-negative integer.");
        }
        Instant timestamp = Instant.ofEpochSecond(record.timestamp());
        if (!timestamp.isAfter(now.minus(MAX_RECORD_AGE)) || timestamp.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw MockAwsException.clientError("TimestampOutOfBoundsException",
                    "The timestamp value passed in the UsageRecord is out of allowed range.");
        }

        License license;
        if (StringUtils.hasText(record.licenseArn())) {
            license = store.license(record.licenseArn()).orElseThrow(() -> MockAwsException.clientError(
                    "InvalidLicenseException", "Ensure the LicenseArn is valid, matches the customer, and usage is within the license activation period."));
            if (record.customerAWSAccountId() != null && !record.customerAWSAccountId().equals(license.customerAWSAccountId)) {
                throw MockAwsException.clientError("InvalidLicenseException",
                        "LicenseArn does not belong to CustomerAWSAccountId " + record.customerAWSAccountId() + ".");
            }
        } else if (StringUtils.hasText(record.customerIdentifier())) {
            // 레거시 연동: CustomerIdentifier + 요청 ProductCode
            license = store.licenseByCustomerIdentifier(record.customerIdentifier(), requestProductCode)
                    .orElseThrow(() -> MockAwsException.clientError("InvalidCustomerIdentifierException",
                            "You have metered usage for a CustomerIdentifier that does not exist."));
        } else {
            throw MockAwsException.clientError("ValidationException",
                    "Each UsageRecord needs LicenseArn (or CustomerIdentifier for legacy integrations).");
        }

        if (StringUtils.hasText(requestProductCode) && !requestProductCode.equals(license.productCode)) {
            throw MockAwsException.clientError("InvalidProductCodeException",
                    "The product code passed does not match the product code used for publishing the product.");
        }
        List<String> meteringDimensions = store.listing(license.productCode)
                .map(MockAwsStore.Listing::meteringDimensions).orElse(List.of());
        if (!meteringDimensions.contains(record.dimension())) {
            throw MockAwsException.clientError("InvalidUsageDimensionException",
                    "The usage dimension does not match one of the UsageDimensions associated with products.");
        }
        return license;
    }
}
