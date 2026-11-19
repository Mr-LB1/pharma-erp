package com.pharma.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 销售退货创建请求。 */
public record SalesReturnCreateRequest(
        @NotNull(message = "原销售出库单不能为空") Long deliveryId,
        Long storeId,
        @NotNull(message = "退货日期不能为空") LocalDate returnDate,
        @Size(max = 500, message = "退货原因长度不能超过500") String reason,
        @NotEmpty(message = "退货明细不能为空") List<@Valid SalesReturnItemRequest> items) {
}
