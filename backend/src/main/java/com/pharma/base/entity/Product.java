package com.pharma.base.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 商品/药品主数据。
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "商品编码不能为空")
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @NotBlank(message = "通用名不能为空")
    @Column(name = "generic_name", nullable = false, length = 200)
    private String genericName;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Column(name = "approval_no", length = 100)
    private String approvalNo;

    @Column(name = "dosage_form", length = 50)
    private String dosageForm;

    @Column(length = 100)
    private String spec;

    @Column(length = 200)
    private String manufacturer;

    @Column(name = "rx_category", length = 20)
    private String rxCategory;

    @Column(name = "storage_cond", length = 20)
    private String storageCond;

    @Column(name = "near_expiry_days")
    private Integer nearExpiryDays;

    @Column(name = "first_audit_status", nullable = false, length = 20)
    private String firstAuditStatus = "PENDING";

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 资料创建人：首营审核「本人回避」依据（P2-10） */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (firstAuditStatus == null) {
            firstAuditStatus = "PENDING";
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getGenericName() {
        return genericName;
    }

    public void setGenericName(String genericName) {
        this.genericName = genericName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public void setTradeName(String tradeName) {
        this.tradeName = tradeName;
    }

    public String getApprovalNo() {
        return approvalNo;
    }

    public void setApprovalNo(String approvalNo) {
        this.approvalNo = approvalNo;
    }

    public String getDosageForm() {
        return dosageForm;
    }

    public void setDosageForm(String dosageForm) {
        this.dosageForm = dosageForm;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getRxCategory() {
        return rxCategory;
    }

    public void setRxCategory(String rxCategory) {
        this.rxCategory = rxCategory;
    }

    public String getStorageCond() {
        return storageCond;
    }

    public void setStorageCond(String storageCond) {
        this.storageCond = storageCond;
    }

    public Integer getNearExpiryDays() {
        return nearExpiryDays;
    }

    public void setNearExpiryDays(Integer nearExpiryDays) {
        this.nearExpiryDays = nearExpiryDays;
    }

    public String getFirstAuditStatus() {
        return firstAuditStatus;
    }

    public void setFirstAuditStatus(String firstAuditStatus) {
        this.firstAuditStatus = firstAuditStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
