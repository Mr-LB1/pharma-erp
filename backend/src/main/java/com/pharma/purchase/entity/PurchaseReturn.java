package com.pharma.purchase.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购退货单：精确批次扣减库存 + 生成负数应付红冲（biz_type=PURCHASE_RETURN）。
 */
@Entity
@Table(name = "purchase_return")
public class PurchaseReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_no", nullable = false, unique = true, length = 50)
    private String returnNo;

    @Column(name = "receive_id")
    private Long receiveId;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "COMPLETED";
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
    }


    /** WMS 下发时间（审批通过即下发，状态 PUSHED）。 */
    @Column(name = "wms_pushed_at")
    private java.time.LocalDateTime wmsPushedAt;

    /** WMS 单号（回传时写入）。 */
    @Column(name = "wms_no", length = 100)
    private String wmsNo;

    /** WMS 回传时间（状态 COMPLETED）。 */
    @Column(name = "wms_callback_at")
    private java.time.LocalDateTime wmsCallbackAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReturnNo() { return returnNo; }
    public void setReturnNo(String returnNo) { this.returnNo = returnNo; }
    public Long getReceiveId() { return receiveId; }
    public void setReceiveId(Long receiveId) { this.receiveId = receiveId; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public LocalDate getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDate returnDate) { this.returnDate = returnDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public java.time.LocalDateTime getWmsPushedAt() { return wmsPushedAt; }
    public void setWmsPushedAt(java.time.LocalDateTime wmsPushedAt) { this.wmsPushedAt = wmsPushedAt; }
    public String getWmsNo() { return wmsNo; }
    public void setWmsNo(String wmsNo) { this.wmsNo = wmsNo; }
    public java.time.LocalDateTime getWmsCallbackAt() { return wmsCallbackAt; }
    public void setWmsCallbackAt(java.time.LocalDateTime wmsCallbackAt) { this.wmsCallbackAt = wmsCallbackAt; }
}
