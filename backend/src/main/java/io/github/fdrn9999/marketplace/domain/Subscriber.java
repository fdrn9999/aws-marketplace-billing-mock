package io.github.fdrn9999.marketplace.domain;

import java.time.Instant;
import java.util.List;

/**
 * AWS Marketplace 계약(구독) 1건에 대한 판매자 측 레코드.
 * QuickStart의 {@code AWSMarketplaceSubscribers} 테이블(가이드 p21~22) 필드를 참고했다.
 *
 * <p>식별 키: 2026-06 Concurrent Agreements 변경으로 AWS 계정 하나가 여러 계약을 가질 수 있으므로
 * 자연 키는 {@code licenseArn}이다. {@code customerIdentifier}는 기존 연동 호환용으로만 보관한다.
 */
public class Subscriber {

    private String subscriberId;
    private String licenseArn;
    private String customerAWSAccountId;
    private String customerIdentifier;
    private String productCode;

    // 등록 폼 정보 (QuickStart RegisterNewMarketplaceCustomer)
    private String companyName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private boolean successfullyRegistered;

    // 구독/계약 상태 (QuickStart EntitlementSQSHandler가 갱신하는 필드)
    private boolean successfullySubscribed;
    private boolean subscriptionExpired;
    private StatusReason expiredReason;
    private boolean freeTrialTermPresent;
    private List<EntitlementSnapshot> entitlements = List.of();
    private Instant termStartAt;
    private Instant lastEntitlementSyncAt;
    /** 마지막으로 반영한 Marketplace 이벤트의 발생 시각 (순서가 뒤바뀐 이벤트를 걸러내는 기준) */
    private Instant lastEventAt;

    private Instant createdAt;
    private Instant updatedAt;

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

    public String getCustomerIdentifier() {
        return customerIdentifier;
    }

    public void setCustomerIdentifier(String customerIdentifier) {
        this.customerIdentifier = customerIdentifier;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public boolean isSuccessfullyRegistered() {
        return successfullyRegistered;
    }

    public void setSuccessfullyRegistered(boolean successfullyRegistered) {
        this.successfullyRegistered = successfullyRegistered;
    }

    public boolean isSuccessfullySubscribed() {
        return successfullySubscribed;
    }

    public void setSuccessfullySubscribed(boolean successfullySubscribed) {
        this.successfullySubscribed = successfullySubscribed;
    }

    public boolean isSubscriptionExpired() {
        return subscriptionExpired;
    }

    public void setSubscriptionExpired(boolean subscriptionExpired) {
        this.subscriptionExpired = subscriptionExpired;
    }

    public StatusReason getExpiredReason() {
        return expiredReason;
    }

    public void setExpiredReason(StatusReason expiredReason) {
        this.expiredReason = expiredReason;
    }

    public boolean isFreeTrialTermPresent() {
        return freeTrialTermPresent;
    }

    public void setFreeTrialTermPresent(boolean freeTrialTermPresent) {
        this.freeTrialTermPresent = freeTrialTermPresent;
    }

    public List<EntitlementSnapshot> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<EntitlementSnapshot> entitlements) {
        this.entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
    }

    public Instant getTermStartAt() {
        return termStartAt;
    }

    public void setTermStartAt(Instant termStartAt) {
        this.termStartAt = termStartAt;
    }

    public Instant getLastEntitlementSyncAt() {
        return lastEntitlementSyncAt;
    }

    public void setLastEntitlementSyncAt(Instant lastEntitlementSyncAt) {
        this.lastEntitlementSyncAt = lastEntitlementSyncAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
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
