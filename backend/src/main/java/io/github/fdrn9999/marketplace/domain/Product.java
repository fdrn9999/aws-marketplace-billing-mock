package io.github.fdrn9999.marketplace.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * AWS Marketplace에 등록된 SaaS 상품. 상품(리스팅) 하나는 과금 모델 하나만 가진다.
 *
 * @param contractPriceUsd 계약 금액(참고용, CONTRACT / CONTRACT_WITH_SUBSCRIPTION). 실제 청구는 AWS가 한다
 * @param dimensions       과금 차원 목록. 첫 번째가 대표 차원이며 사용량 0 레코드에 쓰인다
 */
public record Product(
        String productCode,
        String name,
        String description,
        PricingModel pricingModel,
        BigDecimal contractPriceUsd,
        List<Dimension> dimensions) {

    /**
     * @param meteringUnitPriceUsd 미터링 단가(USD). 미터링하지 않는 차원이면 null
     */
    public record Dimension(String key, String name, String unit, BigDecimal meteringUnitPriceUsd) {
    }

    public Dimension primaryDimension() {
        return dimensions.get(0);
    }

    public Optional<Dimension> dimension(String key) {
        return dimensions.stream().filter(d -> d.key().equals(key)).findFirst();
    }
}
