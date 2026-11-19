package com.pharma.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 应收单据：销售出库自动生成，作废出库联动 VOID。
 */
@Entity
@Table(name = "ar_invoice")
public class ArInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", nullable = false, unique = true, length = 50)
    private String invoiceNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "biz_type", nullable = false, length = 30)
    private String bizType;

    @Column(name = "biz_id", nullable = false)
    private Long bizId;

    @Column(name = "invoice_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal invoiceAmount = BigDecimal.ZERO;

    @Column(name = "settled_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status = "UNSETTLED";

    @Column(length = 500)
    private String remark;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "UNSETTLED";
        if (invoiceAmount == null) invoiceAmount = BigDecimal.ZERO;
        if (settledAmount == null) settledAmount = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public BigDecimal getInvoiceAmount() { return invoiceAmount; }
    public void setInvoiceAmount(BigDecimal invoiceAmount) { this.invoiceAmount = invoiceAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
