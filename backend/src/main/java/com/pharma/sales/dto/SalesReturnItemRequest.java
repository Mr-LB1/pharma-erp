package com.pharma.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 销售退货明细请求：批号须来自原出库单。 */
public record SalesReturnItemRequest(
        @NotNull(message = "商品不能为空") Long productId,
        @NotBlank(message = "退货批号不能为空") @Size(max = 50, message = "批号长度不能超过50") String batchNo,
        @NotNull(message = "退货数量不能为空") @Positive(message = "退货数量必须大于0") BigDecimal quantity,
        @NotNull(message = "退回入库仓库不能为空") Long warehouseId) {
}
