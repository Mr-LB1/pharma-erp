package com.pharma.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 调拨明细请求：指定批次。 */
public record StockTransferItemRequest(
        @NotNull(message = "商品不能为空") Long productId,
        @NotBlank(message = "调拨批号不能为空") @Size(max = 50, message = "批号长度不能超过50") String batchNo,
        @NotNull(message = "调拨数量不能为空") @Positive(message = "调拨数量必须大于0") BigDecimal quantity) {
}
