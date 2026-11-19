package com.pharma.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * 收款核销明细：一笔收款核销到某张应收单的金额。
 */
@Entity
@Table(name = "receipt_item")
public class ReceiptItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Column(name = "ar_invoice_id", nullable = false)
    private Long arInvoiceId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public Long getArInvoiceId() { return arInvoiceId; }
    public void setArInvoiceId(Long arInvoiceId) { this.arInvoiceId = arInvoiceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
