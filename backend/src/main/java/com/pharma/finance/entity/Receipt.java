package com.pharma.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 收款单：按应收单核销（receipt_item）。
 */
@Entity
@Table(name = "receipt")
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no", nullable = false, unique = true, length = 50)
    private String receiptNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(length = 500)
    private String remark;

    /** 收款业务日期（对账单按单据日期分期的依据）。 */
    @Column(name = "receipt_date")
    private LocalDate receiptDate;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (receiptDate == null) receiptDate = createdAt.toLocalDate();
        if (status == null) status = "ACTIVE";
        if (amount == null) amount = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
