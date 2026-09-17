package io.github.fdrn9999.marketplace.mockaws;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 장애 주입 설정. API별로 다음 N번(또는 계속) 오류를 일으켜 재시도와 오류 처리 경로를 시연하고 테스트한다.
 * UNPROCESSED는 BatchMeterUsage 전용이며 호출 수가 아니라 레코드 수만큼 UnprocessedRecords로 돌려준다.
 */
@Component
public class MockAwsFaults {

    public enum Api {
        RESOLVE_CUSTOMER,
        GET_ENTITLEMENTS,
        BATCH_METER_USAGE
    }

    public enum Mode {
        NONE,
        /** ThrottlingException (400) */
        THROTTLE,
        /** InternalServiceErrorException (500) */
        INTERNAL_ERROR,
        /** 레코드를 UnprocessedRecords로 반환 (BatchMeterUsage만 해당) */
        UNPROCESSED
    }

    /** @param remaining 남은 적용 횟수. -1이면 해제할 때까지 계속 적용 */
    public record Fault(Mode mode, int remaining) {
        static final Fault NONE = new Fault(Mode.NONE, 0);
    }

    private final Map<Api, Fault> faults = new EnumMap<>(Api.class);

    public synchronized void set(Api api, Mode mode, int remaining) {
        if (mode == Mode.UNPROCESSED && api != Api.BATCH_METER_USAGE) {
            throw MockAwsException.clientError("ValidationException", "UNPROCESSED는 BATCH_METER_USAGE에만 설정할 수 있습니다");
        }
        faults.put(api, mode == Mode.NONE || remaining == 0 ? Fault.NONE : new Fault(mode, remaining));
    }

    public synchronized Map<Api, Fault> snapshot() {
        Map<Api, Fault> copy = new EnumMap<>(Api.class);
        for (Api api : Api.values()) {
            copy.put(api, faults.getOrDefault(api, Fault.NONE));
        }
        return copy;
    }

    public synchronized void clear() {
        faults.clear();
    }

    /** 호출 단위 장애(THROTTLE, INTERNAL_ERROR)가 설정되어 있으면 1회 소비하고 예외를 던진다. */
    public void checkCall(Api api) {
        Mode mode = consumeIf(api, Mode.THROTTLE, Mode.INTERNAL_ERROR);
        if (mode == Mode.THROTTLE) {
            throw MockAwsException.throttling();
        }
        if (mode == Mode.INTERNAL_ERROR) {
            throw MockAwsException.internalError();
        }
    }

    /** 레코드 1건을 UnprocessedRecords로 돌려야 하면 true (1회 소비). */
    public boolean takeUnprocessed() {
        return consumeIf(Api.BATCH_METER_USAGE, Mode.UNPROCESSED) == Mode.UNPROCESSED;
    }

    private synchronized Mode consumeIf(Api api, Mode... modes) {
        Fault fault = faults.getOrDefault(api, Fault.NONE);
        for (Mode mode : modes) {
            if (fault.mode() == mode) {
                if (fault.remaining() > 0) {
                    int left = fault.remaining() - 1;
                    faults.put(api, left == 0 ? Fault.NONE : new Fault(mode, left));
                }
                return mode;
            }
        }
        return Mode.NONE;
    }
}
