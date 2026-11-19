package com.pharma.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record SalesDeliveryCreateRequest(
        @NotNull(message = "出库仓库不能为空") Long warehouseId,
        @NotNull(message = "销售订单不能为空") Long orderId,
        @NotNull(message = "出库日期不能为空") LocalDate deliveryDate,
        @NotEmpty(message = "出库明细不能为空") List<@Valid SalesDeliveryItemRequest> items) {
}
