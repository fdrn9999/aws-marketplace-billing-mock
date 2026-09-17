package io.github.fdrn9999.marketplace.domain;

import java.time.Instant;

/**
 * BatchMeterUsage로 보고할(또는 보고한) 시간 단위 사용량 버킷.
 * QuickStart의 {@code AWSMarketplaceMeteringRecords} 테이블(가이드 p23)을 참고했다.
 * QuickStart는 전송이 끝나면 pending 항목을 지우지만, 여기서는 UI에 이력을 보여주기 위해 결과와 함께 보관한다.
 *
 * <p>유니크 키: {@code (licenseArn, dimension, hourStart)}
 */
public class MeteringRecord {

    private String id;
    private String subscriberId;
    private String licenseArn;
    private String customerAWSAccountId;
    private String productCode;
    private String dimension;
    private Instant hourStart;
    private long quantity;
    private MeteringStatus status = MeteringStatus.PENDING;
    private String meteringRecordId;
    private int attempts;
    private String lastError;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static String key(String licenseArn, String dimension, Instant hourStart) {
        return licenseArn + "|" + dimension + "|" + hourStart.getEpochSecond();
    }

    public String key() {
        return key(licenseArn, dimension, hourStart);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubscriberId() {
        return subscriberId;
    }

    public void setSubscriberId(String subscriberId) {
        this.subscriberId = subscriberId;
    }

    public String getLicenseArn() {
        return licenseArn;
    }

    public void setLicenseArn(String licenseArn) {
        this.licenseArn = licenseArn;
    }

    public String getCustomerAWSAccountId() {
        return customerAWSAccountId;
    }

    public void setCustomerAWSAccountId(String customerAWSAccountId) {
        this.customerAWSAccountId = customerAWSAccountId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public Instant getHourStart() {
        return hourStart;
    }

    public void setHourStart(Instant hourStart) {
        this.hourStart = hourStart;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public MeteringStatus getStatus() {
        return status;
    }

    public void setStatus(MeteringStatus status) {
        this.status = status;
    }

    public String getMeteringRecordId() {
        return meteringRecordId;
    }

    public void setMeteringRecordId(String meteringRecordId) {
        this.meteringRecordId = meteringRecordId;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
