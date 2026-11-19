package com.pharma.finance.dto;

import java.math.BigDecimal;

/** 付款核销明细视图。 */
public record PaymentItemVO(Long id, Long apInvoiceId, String invoiceNo, BigDecimal amount) {
}
