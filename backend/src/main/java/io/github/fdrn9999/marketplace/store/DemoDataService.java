package io.github.fdrn9999.marketplace.store;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import io.github.fdrn9999.marketplace.common.SimulatedClock;

/** 기동 시 시드 데이터를 적재하고, 데모 초기화 요청 시 시계와 데이터를 처음 상태로 되돌린다. */
@Service
public class DemoDataService implements ApplicationRunner {

    private final SimulatedClock clock;
    private final List<SeedLoader> loaders;

    public DemoDataService(SimulatedClock clock, List<SeedLoader> loaders) {
        this.clock = clock;
        this.loaders = loaders;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadAll();
    }

    public synchronized void reset() {
        clock.reset();
        loadAll();
    }

    private void loadAll() {
        loaders.forEach(SeedLoader::load);
    }
}
