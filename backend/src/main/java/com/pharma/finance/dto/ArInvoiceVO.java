package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 应收单据视图（outstanding = 未结金额）。 */
public record ArInvoiceVO(Long id,
                          String invoiceNo,
                          Long customerId,
                          String customerName,
                          String bizType,
                          Long bizId,
                          BigDecimal invoiceAmount,
                          BigDecimal settledAmount,
                          BigDecimal outstanding,
                          String status,
                          String remark,
                          String createdBy,
                          LocalDateTime createdAt) {
}
