package io.github.fdrn9999.marketplace.domain;

public enum MeteringStatus {
    /** 미터링 작업 대기 중 (QuickStart의 meteringPending 플래그에 해당) */
    PENDING,
    /** 실행 중인 미터링 작업이 전송 대상으로 잡음 */
    SENDING,
    /** BatchMeterUsage가 수락함 (Status=Success) */
    SUCCESS,
    /** BatchMeterUsage가 DuplicateRecord를 반환함: 이미 반영되었으므로 종료 */
    DUPLICATE,
    /** 더 이상 재시도하지 않음 (CustomerNotSubscribed, 24시간 초과, 재시도 한도 초과) */
    FAILED;

    public boolean isFinal() {
        return this == SUCCESS || this == DUPLICATE || this == FAILED;
    }
}
