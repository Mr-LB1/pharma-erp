package com.pharma.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 仓库调拨创建请求。 */
public record StockTransferCreateRequest(
        @NotNull(message = "调出仓库不能为空") Long fromWarehouseId,
        @NotNull(message = "调入仓库不能为空") Long toWarehouseId,
        @NotNull(message = "调拨日期不能为空") LocalDate transferDate,
        @Size(max = 500, message = "备注长度不能超过500") String remark,
        @NotEmpty(message = "调拨明细不能为空") List<@Valid StockTransferItemRequest> items) {
}
