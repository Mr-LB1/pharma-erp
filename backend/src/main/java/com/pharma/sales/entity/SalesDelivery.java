package com.pharma.sales.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售出库单。
 */
@Entity
@Table(name = "sales_delivery")
public class SalesDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_no", nullable = false, unique = true, length = 50)
    private String deliveryNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 出库金额（Σ 数量 × 原订单单价）：审批金额分级与待审列表使用。 */
    @Column(name = "total_amount", nullable = false)
    private java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;

    /** 出库仓库（库存 location id）：审批通过后才执行，需持久化。 */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = "ACTIVE";
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeliveryNo() {
        return deliveryNo;
    }

    public void setDeliveryNo(String deliveryNo) {
        this.deliveryNo = deliveryNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public java.math.BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(java.math.BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
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

    public java.time.LocalDateTime getWmsPushedAt() { return wmsPushedAt; }
    public void setWmsPushedAt(java.time.LocalDateTime wmsPushedAt) { this.wmsPushedAt = wmsPushedAt; }
    public String getWmsNo() { return wmsNo; }
    public void setWmsNo(String wmsNo) { this.wmsNo = wmsNo; }
    public java.time.LocalDateTime getWmsCallbackAt() { return wmsCallbackAt; }
    public void setWmsCallbackAt(java.time.LocalDateTime wmsCallbackAt) { this.wmsCallbackAt = wmsCallbackAt; }
}
