package io.github.fdrn9999.marketplace.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml의 {@code app.*} 설정을 타입으로 바인딩한다. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        /* 데모 전용 API(/api/admin/**, /mock-aws/_sim/**) 활성화 여부 */
        @DefaultValue("true") boolean demoMode,
        /* Fulfillment URL이 구매자를 보낼 프론트엔드 주소. 비어 있으면 상대 경로로 리디렉션 */
        @DefaultValue("") String webBaseUrl,
        /* Marketplace 이벤트 수신 API(/api/internal/marketplace-events) 공유 비밀값 */
        @DefaultValue("local-dev-event-secret") String eventSecret,
        @DefaultValue MockAws mockAws,
        @DefaultValue Entitlement entitlement,
        @DefaultValue Metering metering) {

    /** 로컬 데모용 이벤트 비밀값 (저장소에 공개되어 있으므로 데모 모드에서만 허용) */
    public static final String DEFAULT_EVENT_SECRET = "local-dev-event-secret";

    public AppProperties {
        boolean unsafeSecret = eventSecret == null || eventSecret.isBlank() || DEFAULT_EVENT_SECRET.equals(eventSecret);
        if (!demoMode && unsafeSecret) {
            throw new IllegalStateException(
                    "데모 모드가 아니면 이벤트 비밀값을 환경변수 APP_EVENT_SECRET으로 지정해야 합니다 (기본값 사용 불가)");
        }
    }

    public record MockAws(
            /* Mock AWS API 주소. 비어 있으면 이 서버 자신(http://localhost:{port}) */
            @DefaultValue("") String baseUrl,
            /* [Mock 정책] 구매 시뮬레이터가 발급하는 등록 토큰의 유효 시간 */
            @DefaultValue("1h") Duration registrationTokenTtl) {
    }

    public record Entitlement(
            /* 캐시된 Entitlement가 이보다 오래되면 권한 검사 시 GetEntitlements로 재동기화 */
            @DefaultValue("15m") Duration refreshAfter,
            /* GetEntitlements 장애 시 캐시를 신뢰하는 최대 기간. 넘으면 거부(fail-closed) */
            @DefaultValue("24h") Duration maxStaleness) {
    }

    public record Metering(
            /* BatchMeterUsage 요청당 최대 레코드 수 (AWS 제한 25) */
            @DefaultValue("25") int batchSize,
            /* [Mock 정책] UnprocessedRecords 재시도 한도. 넘으면 FAILED */
            @DefaultValue("5") int maxAttempts,
            /* Throttling/5xx 발생 시 호출 단위 재시도 횟수 */
            @DefaultValue("3") int callRetries,
            /* [Mock 정책] 사용량 0 레코드를 보충할 최대 과거 시간 수 */
            @DefaultValue("3") int zeroUsageBackfillHours,
            /* AWS는 이벤트 발생 후 24시간 이상 지난 레코드를 거부한다 */
            @DefaultValue("24h") Duration maxRecordAge,
            /* 자동 실행 주기. 0이면 비활성(대시보드나 관리 API로 수동 실행) */
            @DefaultValue("0s") Duration autoRunInterval) {
    }
}
