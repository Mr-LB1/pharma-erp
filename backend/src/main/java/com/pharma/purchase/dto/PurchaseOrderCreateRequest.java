package com.pharma.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record PurchaseOrderCreateRequest(
        @NotNull(message = "供应商不能为空") Long supplierId,
        @NotNull(message = "订单日期不能为空") LocalDate orderDate,
        @NotEmpty(message = "订单明细不能为空") List<@Valid PurchaseOrderItemRequest> items) {
}
