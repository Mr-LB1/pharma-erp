package com.pharma.base.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * 供应商主数据。
 */
@Entity
@Table(name = "supplier")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "供应商名称不能为空")
    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "credit_code", length = 50)
    private String creditCode;

    @Column(name = "license_no", length = 100)
    private String licenseNo;

    @Column(name = "license_expire")
    private LocalDate licenseExpire;

    @Column(name = "gsp_cert", length = 100)
    private String gspCert;

    @Column(name = "first_audit_status", nullable = false, length = 20)
    private String firstAuditStatus = "PENDING";

    @Column(name = "settle_type", length = 20)
    private String settleType;

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

    public String getCreditCode() {
        return creditCode;
    }

    public void setCreditCode(String creditCode) {
        this.creditCode = creditCode;
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

    public String getGspCert() {
        return gspCert;
    }

    public void setGspCert(String gspCert) {
        this.gspCert = gspCert;
    }

    public String getFirstAuditStatus() {
        return firstAuditStatus;
    }

    public void setFirstAuditStatus(String firstAuditStatus) {
        this.firstAuditStatus = firstAuditStatus;
    }

    public String getSettleType() {
        return settleType;
    }

    public void setSettleType(String settleType) {
        this.settleType = settleType;
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
