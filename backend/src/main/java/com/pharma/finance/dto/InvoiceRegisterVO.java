package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 发票登记视图。 */
public record InvoiceRegisterVO(Long id,
                                String invoiceNo,
                                String bizType,
                                Long bizId,
                                BigDecimal amount,
                                String status,
                                String remark,
                                String createdBy,
                                LocalDateTime createdAt) {
}
