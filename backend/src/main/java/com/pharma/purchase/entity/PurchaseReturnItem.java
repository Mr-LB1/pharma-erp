package com.pharma.purchase.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * 采购退货明细：指定批次精确扣减，unit_cost 取批次原成本（红冲金额依据）。
 */
@Entity
@Table(name = "purchase_return_item")
public class PurchaseReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_id", nullable = false)
    private Long returnId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;


    /** WMS 回传实际数量（未回传为 null）。 */
    @Column(name = "actual_qty")
    private java.math.BigDecimal actualQty;

    /** 实际差异 = actualQty - 单据量（未回传为 null）。 */
    @Column(name = "qty_diff")
    private java.math.BigDecimal qtyDiff;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }

    public java.math.BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(java.math.BigDecimal actualQty) { this.actualQty = actualQty; }
    public java.math.BigDecimal getQtyDiff() { return qtyDiff; }
    public void setQtyDiff(java.math.BigDecimal qtyDiff) { this.qtyDiff = qtyDiff; }
}
