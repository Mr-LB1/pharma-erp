package com.pharma.inventory.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 盘点明细（book=账面，actual=实盘，diff=盈亏=actual-book）。
 */
@Entity
@Table(name = "stocktake_item")
public class StocktakeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stocktake_id", nullable = false)
    private Long stocktakeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "batch_no", nullable = false, length = 50)
    private String batchNo;

    @Column(name = "book_qty", nullable = false)
    private BigDecimal bookQty;

    @Column(name = "actual_qty", nullable = false)
    private BigDecimal actualQty;

    @Column(name = "diff_qty", nullable = false)
    private BigDecimal diffQty;

    @Column(name = "produce_date")
    private LocalDate produceDate;

    @Column(name = "expire_date")
    private LocalDate expireDate;

    /** 提交时点重新读取的账面数量（create 的 book_qty 仅作创建快照展示，P0-8） */
    @Column(name = "committed_book_qty")
    private BigDecimal committedBookQty;

    /** 提交时点重算的盈亏 = actual_qty - committed_book_qty */
    @Column(name = "committed_diff_qty")
    private BigDecimal committedDiffQty;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStocktakeId() {
        return stocktakeId;
    }

    public void setStocktakeId(Long stocktakeId) {
        this.stocktakeId = stocktakeId;
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

    public BigDecimal getBookQty() {
        return bookQty;
    }

    public void setBookQty(BigDecimal bookQty) {
        this.bookQty = bookQty;
    }

    public BigDecimal getActualQty() {
        return actualQty;
    }

    public void setActualQty(BigDecimal actualQty) {
        this.actualQty = actualQty;
    }

    public BigDecimal getDiffQty() {
        return diffQty;
    }

    public void setDiffQty(BigDecimal diffQty) {
        this.diffQty = diffQty;
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

    public BigDecimal getCommittedBookQty() {
        return committedBookQty;
    }

    public void setCommittedBookQty(BigDecimal committedBookQty) {
        this.committedBookQty = committedBookQty;
    }

    public BigDecimal getCommittedDiffQty() {
        return committedDiffQty;
    }

    public void setCommittedDiffQty(BigDecimal committedDiffQty) {
        this.committedDiffQty = committedDiffQty;
    }
}
