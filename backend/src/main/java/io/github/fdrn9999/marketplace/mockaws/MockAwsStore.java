package io.github.fdrn9999.marketplace.mockaws;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Mock AWS 쪽 상태 = "AWS가 가지고 있는 데이터".
 * 앱(판매자) 저장소와 분리되어 있고, 앱은 HTTP API로만 이 데이터에 접근한다.
 */
@Component
public class MockAwsStore {

    /** 리스팅(상품) 설정: 과금 모델, 미터링 차원, 계약 차원 */
    public record Listing(String productCode, String pricingModel, List<String> meteringDimensions,
            List<ContractDimension> contractDimensions) {
    }

    public record ContractDimension(String dimension, int quantity, int durationDays) {
    }

    /** 구매 1건 = 라이선스 1개 (Concurrent Agreements 기준) */
    public static final class License {
        public final String licenseArn;
        public final String customerAWSAccountId;
        public final String customerIdentifier;
        public final String productCode;
        public final boolean freeTrial;
        public volatile LicenseState state;

        public License(String licenseArn, String customerAWSAccountId, String customerIdentifier,
                String productCode, boolean freeTrial, LicenseState state) {
            this.licenseArn = licenseArn;
            this.customerAWSAccountId = customerAWSAccountId;
            this.customerIdentifier = customerIdentifier;
            this.productCode = productCode;
            this.freeTrial = freeTrial;
            this.state = state;
        }
    }

    public enum LicenseState {
        ACTIVE,
        CANCELLED
    }

    public static final class StoredEntitlement {
        public final String licenseArn;
        public final String dimension;
        public volatile int integerValue;
        public volatile Instant expirationDate;

        public StoredEntitlement(String licenseArn, String dimension, int integerValue, Instant expirationDate) {
            this.licenseArn = licenseArn;
            this.dimension = dimension;
            this.integerValue = integerValue;
            this.expirationDate = expirationDate;
        }
    }

    public static final class StoredToken {
        public final String token;
        public final String licenseArn;
        public final Instant expiresAt;
        public volatile boolean redeemed;

        public StoredToken(String token, String licenseArn, Instant expiresAt, boolean redeemed) {
            this.token = token;
            this.licenseArn = licenseArn;
            this.expiresAt = expiresAt;
            this.redeemed = redeemed;
        }
    }

    /** BatchMeterUsage로 수락된 레코드 (중복 판정용) */
    public record LedgerEntry(String meteringRecordId, int quantity, Instant receivedAt) {
    }

    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final Map<String, License> licenses = new ConcurrentHashMap<>();
    private final List<StoredEntitlement> entitlements = new ArrayList<>();
    private final Map<String, StoredToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, LedgerEntry> ledger = new ConcurrentHashMap<>();

    public synchronized void replaceAll(List<Listing> newListings, List<License> newLicenses,
            List<StoredEntitlement> newEntitlements, List<StoredToken> newTokens) {
        listings.clear();
        newListings.forEach(l -> listings.put(l.productCode(), l));
        licenses.clear();
        newLicenses.forEach(l -> licenses.put(l.licenseArn, l));
        entitlements.clear();
        entitlements.addAll(newEntitlements);
        tokens.clear();
        newTokens.forEach(t -> tokens.put(t.token, t));
        ledger.clear();
    }

    public Optional<Listing> listing(String productCode) {
        return productCode == null ? Optional.empty() : Optional.ofNullable(listings.get(productCode));
    }

    public Optional<License> license(String licenseArn) {
        return licenseArn == null ? Optional.empty() : Optional.ofNullable(licenses.get(licenseArn));
    }

    /** 레거시 연동: CustomerIdentifier + ProductCode로 라이선스 찾기 */
    public Optional<License> licenseByCustomerIdentifier(String customerIdentifier, String productCode) {
        return licenses.values().stream()
                .filter(l -> l.customerIdentifier != null && l.customerIdentifier.equals(customerIdentifier))
                .filter(l -> productCode == null || l.productCode.equals(productCode))
                .findFirst();
    }

    public void addLicense(License license) {
        licenses.put(license.licenseArn, license);
    }

    public synchronized List<StoredEntitlement> entitlements() {
        return List.copyOf(entitlements);
    }

    public synchronized void addEntitlement(StoredEntitlement entitlement) {
        entitlements.add(entitlement);
    }

    public Optional<StoredToken> token(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokens.get(token));
    }

    public void addToken(StoredToken token) {
        tokens.put(token.token, token);
    }

    /** 토큰을 1회용으로 소비한다. 이미 쓰인 토큰이면 false. */
    public synchronized boolean redeem(StoredToken token) {
        if (token.redeemed) {
            return false;
        }
        token.redeemed = true;
        return true;
    }

    /**
     * 중복 판정 키가 없으면 새로 기록하고 그 값을, 있으면 기존 값을 반환한다.
     * 호출자는 반환값의 수량을 비교해 Success(멱등 재전송)인지 DuplicateRecord(수량 다름)인지 판단한다.
     */
    public LedgerEntry recordIfAbsent(String dedupeKey, LedgerEntry candidate) {
        LedgerEntry existing = ledger.putIfAbsent(dedupeKey, candidate);
        return existing == null ? candidate : existing;
    }

    public int ledgerSize() {
        return ledger.size();
    }
}
