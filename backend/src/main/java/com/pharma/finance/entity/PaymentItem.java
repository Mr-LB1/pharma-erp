package com.pharma.finance.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * 付款核销明细：一笔付款核销到某张应付单的金额。
 */
@Entity
@Table(name = "payment_item")
public class PaymentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "ap_invoice_id", nullable = false)
    private Long apInvoiceId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public Long getApInvoiceId() { return apInvoiceId; }
    public void setApInvoiceId(Long apInvoiceId) { this.apInvoiceId = apInvoiceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
