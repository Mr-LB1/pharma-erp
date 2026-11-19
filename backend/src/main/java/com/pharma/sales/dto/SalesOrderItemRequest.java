package com.pharma.sales.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SalesOrderItemRequest(
        @NotNull(message = "商品不能为空") Long productId,
        @NotNull(message = "数量不能为空") @Positive(message = "数量必须大于0") BigDecimal quantity,
        @NotNull(message = "单价不能为空") @DecimalMin(value = "0", message = "单价不能为负") BigDecimal unitPrice) {
}
