package com.pharma.inventory.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 仓库调拨单：一事务内 from 仓扣减 + to 仓 upsert，写两条 ref_type=TRANSFER 流水。
 */
@Entity
@Table(name = "stock_transfer")
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_no", nullable = false, unique = true, length = 50)
    private String transferNo;

    @Column(name = "from_warehouse_id", nullable = false)
    private Long fromWarehouseId;

    @Column(name = "to_warehouse_id", nullable = false)
    private Long toWarehouseId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    /** 调拨成本合计（Σ 数量 × 批次成本）：审批金额分级与待审列表使用。 */
    @Column(name = "total_amount", nullable = false)
    private java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;

    @Column(length = 500)
    private String remark;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "COMPLETED";
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
    public String getTransferNo() { return transferNo; }
    public void setTransferNo(String transferNo) { this.transferNo = transferNo; }
    public Long getFromWarehouseId() { return fromWarehouseId; }
    public void setFromWarehouseId(Long fromWarehouseId) { this.fromWarehouseId = fromWarehouseId; }
    public Long getToWarehouseId() { return toWarehouseId; }
    public void setToWarehouseId(Long toWarehouseId) { this.toWarehouseId = toWarehouseId; }
    public LocalDate getTransferDate() { return transferDate; }
    public void setTransferDate(LocalDate transferDate) { this.transferDate = transferDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public java.math.BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(java.math.BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
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
