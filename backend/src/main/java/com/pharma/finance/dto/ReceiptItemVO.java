package com.pharma.finance.dto;

import java.math.BigDecimal;

/** 收款核销明细视图。 */
public record ReceiptItemVO(Long id, Long arInvoiceId, String invoiceNo, BigDecimal amount) {
}
