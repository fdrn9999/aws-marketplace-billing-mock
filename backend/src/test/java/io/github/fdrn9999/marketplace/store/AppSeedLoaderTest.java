package io.github.fdrn9999.marketplace.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.fdrn9999.marketplace.common.SimulatedClock;
import io.github.fdrn9999.marketplace.domain.MeteringRecord;
import io.github.fdrn9999.marketplace.domain.MeteringStatus;
import io.github.fdrn9999.marketplace.domain.PricingModel;
import io.github.fdrn9999.marketplace.domain.Subscriber;

@SpringBootTest
class AppSeedLoaderTest {

    @Autowired
    DemoDataService demoData;
    @Autowired
    SimulatedClock clock;
    @Autowired
    ProductRepository products;
    @Autowired
    SubscriberRepository subscribers;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    MeteringRecordRepository meteringRecords;

    @BeforeEach
    void reset() {
        demoData.reset();
    }

    @Test
    @DisplayName("과금 모델 3종 상품과 시나리오별 구독자 6명이 적재된다")
    void loadsProductsAndSubscribers() {
        assertThat(products.findAll()).extracting(p -> p.pricingModel())
                .containsExactlyInAnyOrder(PricingModel.SUBSCRIPTION, PricingModel.CONTRACT,
                        PricingModel.CONTRACT_WITH_SUBSCRIPTION);
        assertThat(subscribers.findAll()).hasSize(6);
        assertThat(subscribers.findByLicenseArn("arn:aws:license-manager::222222222222:license:l-contract-active"))
                .map(Subscriber::getSubscriberId).contains("sub-contract-active");
    }

    @Test
    @DisplayName("상대 시각이 기동 시각 기준으로 계산된다 (계약 만료 6일 전)")
    void resolvesRelativeTimes() {
        Subscriber contract = subscribers.findById("sub-contract-active").orElseThrow();
        long minutesLeft = ChronoUnit.MINUTES.between(clock.now(), contract.getEntitlements().get(0).expirationDate());
        // 시드 적재 시각과 검증 시각 사이의 짧은 차이는 허용한다
        assertThat(minutesLeft).isBetween(6L * 24 * 60 - 1, 6L * 24 * 60);
    }

    @Test
    @DisplayName("미터링 버킷은 사용량 이벤트의 metered 수량을 시간 단위로 합산해 만든다")
    void buildsMeteringBucketsFromUsage() {
        List<MeteringRecord> usageActive = meteringRecords.findBySubscriber("sub-usage-active");
        assertThat(usageActive).hasSize(5);
        assertThat(usageActive).allSatisfy(r -> assertThat(r.getHourStart().getEpochSecond() % 3600).isZero());
        assertThat(meteringRecords.findBySubscriber("sub-contract-active")).isEmpty();
        assertThat(meteringRecords.findBySubscriber("sub-hybrid-overage"))
                .extracting(MeteringRecord::getStatus)
                .containsExactlyInAnyOrder(MeteringStatus.PENDING, MeteringStatus.PENDING, MeteringStatus.SUCCESS);
        assertThat(usageEvents.sumIncluded("sub-contract-active", "analysis_run",
                subscribers.findById("sub-contract-active").orElseThrow().getTermStartAt())).isEqualTo(95);
    }
}
