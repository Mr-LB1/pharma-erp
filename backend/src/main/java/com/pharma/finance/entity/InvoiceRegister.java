package com.pharma.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票登记：收票/开票留痕（可关联业务单据）。
 */
@Entity
@Table(name = "invoice_register")
public class InvoiceRegister {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", nullable = false, length = 100)
    private String invoiceNo;

    @Column(name = "biz_type", length = 30)
    private String bizType;

    @Column(name = "biz_id")
    private Long bizId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(length = 500)
    private String remark;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
        if (amount == null) amount = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
