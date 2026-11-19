package com.pharma.base.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 客户主数据。
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "客户名称不能为空")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "license_no", length = 100)
    private String licenseNo;

    @Column(name = "license_expire")
    private LocalDate licenseExpire;

    @Column(name = "credit_level", length = 20)
    private String creditLevel;

    @Column(name = "credit_limit", precision = 18, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "first_audit_status", nullable = false, length = 20)
    private String firstAuditStatus = "PENDING";

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 资料创建人：首营审核「本人回避」依据（P2-10） */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    @PrePersist
    public void prePersist() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public LocalDate getLicenseExpire() {
        return licenseExpire;
    }

    public void setLicenseExpire(LocalDate licenseExpire) {
        this.licenseExpire = licenseExpire;
    }

    public String getCreditLevel() {
        return creditLevel;
    }

    public void setCreditLevel(String creditLevel) {
        this.creditLevel = creditLevel;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
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
}
