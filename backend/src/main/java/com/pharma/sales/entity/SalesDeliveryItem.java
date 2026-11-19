package com.pharma.sales.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * 销售出库明细（锁批号，unit_cost 为结转成本）。
 */
@Entity
@Table(name = "sales_delivery_item")
public class SalesDeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost;


    /** WMS 回传实际数量（未回传为 null）。 */
    @Column(name = "actual_qty")
    private java.math.BigDecimal actualQty;

    /** 实际差异 = actualQty - 单据量（未回传为 null）。 */
    @Column(name = "qty_diff")
    private java.math.BigDecimal qtyDiff;
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(Long deliveryId) {
        this.deliveryId = deliveryId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    public java.math.BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(java.math.BigDecimal actualQty) { this.actualQty = actualQty; }
    public java.math.BigDecimal getQtyDiff() { return qtyDiff; }
    public void setQtyDiff(java.math.BigDecimal qtyDiff) { this.qtyDiff = qtyDiff; }
}
