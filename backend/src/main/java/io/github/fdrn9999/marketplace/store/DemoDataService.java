package io.github.fdrn9999.marketplace.store;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.common.DemoStateLock;
import io.github.fdrn9999.marketplace.common.SimulatedClock;

/** 기동 시 시드 데이터를 적재하고, 데모 초기화 요청 시 시계와 데이터를 처음 상태로 되돌린다. */
@Service
public class DemoDataService implements ApplicationRunner {

    static final Duration RESET_WAIT = Duration.ofSeconds(10);

    private final SimulatedClock clock;
    private final List<SeedLoader> loaders;
    private final DemoStateLock stateLock;

    public DemoDataService(SimulatedClock clock, List<SeedLoader> loaders, DemoStateLock stateLock) {
        this.clock = clock;
        this.loaders = loaders;
        this.stateLock = stateLock;
    }

    @Override
    public void run(ApplicationArguments args) {
        stateLock.write(this::loadAll, RESET_WAIT);
    }

    /** 진행 중인 앱 요청과 미터링 작업이 끝나기를 기다렸다가 초기화한다 */
    public void reset() {
        stateLock.write(() -> {
            clock.reset();
            loadAll();
        }, RESET_WAIT);
    }

    private void loadAll() {
        loaders.forEach(SeedLoader::load);
    }
}
