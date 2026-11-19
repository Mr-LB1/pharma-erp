package com.pharma.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 销售退货单视图。 */
public record SalesReturnVO(Long id,
                            String returnNo,
                            Long deliveryId,
                            String deliveryNo,
                            Long customerId,
                            String customerName,
                            Long storeId,
                            LocalDate returnDate,
                            String status,
                            BigDecimal totalAmount,
                            String reason,
                            String createdBy,
                            LocalDateTime createdAt,
                            List<SalesReturnItemVO> items) {
}
