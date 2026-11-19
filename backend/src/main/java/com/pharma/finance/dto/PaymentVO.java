package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 付款单视图。 */
public record PaymentVO(Long id,
                        String paymentNo,
                        Long supplierId,
                        String supplierName,
                        BigDecimal amount,
                        String status,
                        String remark,
                        String createdBy,
                        LocalDateTime createdAt,
                        List<PaymentItemVO> items) {
}
