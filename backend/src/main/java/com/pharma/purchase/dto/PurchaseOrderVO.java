package com.pharma.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderVO(Long id,
                              String orderNo,
                              Long supplierId,
                              String supplierName,
                              LocalDate orderDate,
                              String status,
                              BigDecimal totalAmount,
                              String createdBy,
                              LocalDateTime createdAt,
                              List<PurchaseOrderItemVO> items) {
}
