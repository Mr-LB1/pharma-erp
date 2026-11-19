package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 收款单视图。 */
public record ReceiptVO(Long id,
                        String receiptNo,
                        Long customerId,
                        String customerName,
                        BigDecimal amount,
                        String status,
                        String remark,
                        String createdBy,
                        LocalDateTime createdAt,
                        List<ReceiptItemVO> items) {
}
