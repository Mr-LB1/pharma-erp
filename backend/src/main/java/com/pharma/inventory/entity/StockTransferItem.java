package com.pharma.inventory.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** 仓库调拨明细：指定批次整批/部分调拨。 */
@Entity
@Table(name = "stock_transfer_item")
public class StockTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(nullable = false)
    private BigDecimal quantity;


    /** WMS 回传实际数量（未回传为 null）。 */
    @Column(name = "actual_qty")
    private java.math.BigDecimal actualQty;

    /** 实际差异 = actualQty - 单据量（未回传为 null）。 */
    @Column(name = "qty_diff")
    private java.math.BigDecimal qtyDiff;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTransferId() { return transferId; }
    public void setTransferId(Long transferId) { this.transferId = transferId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public java.math.BigDecimal getActualQty() { return actualQty; }
    public void setActualQty(java.math.BigDecimal actualQty) { this.actualQty = actualQty; }
    public java.math.BigDecimal getQtyDiff() { return qtyDiff; }
    public void setQtyDiff(java.math.BigDecimal qtyDiff) { this.qtyDiff = qtyDiff; }
}
