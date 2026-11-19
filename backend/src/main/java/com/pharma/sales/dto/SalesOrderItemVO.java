package com.pharma.sales.dto;

import java.math.BigDecimal;

public record SalesOrderItemVO(Long id,
                               Long productId,
                               String productName,
                               BigDecimal quantity,
                               BigDecimal unitPrice,
                               BigDecimal amount) {
}
