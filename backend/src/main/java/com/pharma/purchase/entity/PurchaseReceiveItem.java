package com.pharma.purchase.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购入库明细（追溯起点）。
 */
@Entity
@Table(name = "purchase_receive_item")
public class PurchaseReceiveItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receive_id", nullable = false)
    private Long receiveId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(name = "produce_date")
    private LocalDate produceDate;

    @Column(name = "expire_date", nullable = false)
    private LocalDate expireDate;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost;

    @Column(name = "inspection_no", length = 100)
    private String inspectionNo;


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

    public Long getReceiveId() {
        return receiveId;
    }

    public void setReceiveId(Long receiveId) {
        this.receiveId = receiveId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public LocalDate getProduceDate() {
        return produceDate;
    }

    public void setProduceDate(LocalDate produceDate) {
        this.produceDate = produceDate;
    }

    public LocalDate getExpireDate() {
        return expireDate;
    }

    public void setExpireDate(LocalDate expireDate) {
        this.expireDate = expireDate;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    public String getInspectionNo() {
        return inspectionNo;
    }

    public void setInspectionNo(String inspectionNo) {
        this.inspectionNo = inspectionNo;
    }

    public java.math.BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(java.math.BigDecimal actualQty) { this.actualQty = actualQty; }
    public java.math.BigDecimal getQtyDiff() { return qtyDiff; }
    public void setQtyDiff(java.math.BigDecimal qtyDiff) { this.qtyDiff = qtyDiff; }
}
