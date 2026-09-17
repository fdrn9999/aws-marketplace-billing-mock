package io.github.fdrn9999.marketplace.metering;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.fdrn9999.marketplace.common.ApiException;
import io.github.fdrn9999.marketplace.config.AppProperties;

/**
 * 미터링 작업 자동 실행 (QuickStart의 EventBridge Hourly 규칙 역할).
 * {@code app.metering.auto-run-interval}이 0이면 꺼져 있고, 데모에서는 대시보드 버튼으로 수동 실행한다.
 */
@Component
public class MeteringScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MeteringScheduler.class);

    private final MeteringJob job;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public MeteringScheduler(MeteringJob job, AppProperties properties) {
        this.job = job;
        this.interval = properties.metering().autoRunInterval();
    }

    @Override
    public synchronized void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metering-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("미터링 자동 실행 활성화: {} 간격", interval);
    }

    private void runSafely() {
        try {
            job.run();
        } catch (ApiException e) {
            log.debug("자동 미터링 건너뜀: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("자동 미터링 실패", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
