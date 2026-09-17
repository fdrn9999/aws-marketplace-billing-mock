package io.github.fdrn9999.marketplace.mockaws;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.License;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.LicenseState;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.Listing;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredEntitlement;
import io.github.fdrn9999.marketplace.mockaws.MockAwsStore.StoredToken;
import io.github.fdrn9999.marketplace.store.RelativeTime;
import io.github.fdrn9999.marketplace.store.SeedLoader;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** AWS 측 시드 데이터 로더: {@code classpath:mock-data/aws/*.json}. 초기화 시 장애 설정과 호출 로그도 비운다. */
@Component
@Order(0)
public class MockAwsSeedLoader implements SeedLoader {

    private static final String BASE = "mock-data/aws/";

    private final JsonMapper jsonMapper;
    private final SimulatedClock clock;
    private final MockAwsStore store;
    private final MockAwsFaults faults;
    private final MockAwsCallLog callLog;

    public MockAwsSeedLoader(JsonMapper jsonMapper, SimulatedClock clock, MockAwsStore store, MockAwsFaults faults,
            MockAwsCallLog callLog) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.store = store;
        this.faults = faults;
        this.callLog = callLog;
    }

    @Override
    public void load() {
        Instant now = clock.now();
        List<Listing> listings = read("listings.json", new TypeReference<List<Listing>>() { });
        List<License> licenses = read("licenses.json", new TypeReference<List<LicenseSeed>>() { }).stream()
                .map(s -> new License(s.licenseArn(), s.customerAWSAccountId(), s.customerIdentifier(),
                        s.productCode(), Boolean.TRUE.equals(s.freeTrial()), s.state()))
                .toList();
        List<StoredEntitlement> entitlements = read("entitlements.json", new TypeReference<List<EntitlementSeed>>() { })
                .stream()
                .map(s -> new StoredEntitlement(s.licenseArn(), s.dimension(), s.integerValue(),
                        RelativeTime.parse(s.expirationDate(), now)))
                .toList();
        List<StoredToken> tokens = read("registration-tokens.json", new TypeReference<List<TokenSeed>>() { }).stream()
                .map(s -> new StoredToken(s.token(), s.licenseArn(), RelativeTime.parse(s.expiresAt(), now),
                        Boolean.TRUE.equals(s.redeemed())))
                .toList();
        store.replaceAll(listings, licenses, entitlements, tokens);
        faults.clear();
        callLog.clear();
    }

    private <T> T read(String file, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource(BASE + file).getInputStream()) {
            return jsonMapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("시드 파일을 읽을 수 없습니다: " + BASE + file, e);
        }
    }

    record LicenseSeed(String licenseArn, String customerAWSAccountId, String customerIdentifier, String productCode,
            LicenseState state, Boolean freeTrial) {
    }

    record EntitlementSeed(String licenseArn, String dimension, int integerValue, String expirationDate) {
    }

    record TokenSeed(String token, String licenseArn, String expiresAt, Boolean redeemed) {
    }
}
