package com.pharma.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SalesOrderVO(Long id,
                           String orderNo,
                           Long customerId,
                           String customerName,
                           LocalDate orderDate,
                           String status,
                           BigDecimal totalAmount,
                           String createdBy,
                           LocalDateTime createdAt,
                           List<SalesOrderItemVO> items) {
}
