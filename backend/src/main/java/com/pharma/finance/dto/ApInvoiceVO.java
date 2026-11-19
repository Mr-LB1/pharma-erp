package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 应付单据视图（outstanding = 未结金额）。 */
public record ApInvoiceVO(Long id,
                          String invoiceNo,
                          Long supplierId,
                          String supplierName,
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
