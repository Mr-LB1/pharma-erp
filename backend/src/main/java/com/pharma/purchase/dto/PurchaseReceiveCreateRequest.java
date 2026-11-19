package com.pharma.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record PurchaseReceiveCreateRequest(
        @NotNull(message = "入库仓库不能为空") Long warehouseId,
        @NotNull(message = "采购订单不能为空") Long orderId,
        @NotNull(message = "入库日期不能为空") LocalDate receiveDate,
        @NotEmpty(message = "入库明细不能为空") List<@Valid PurchaseReceiveItemRequest> items) {
}
