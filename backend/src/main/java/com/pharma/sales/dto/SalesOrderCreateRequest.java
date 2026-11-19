package com.pharma.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record SalesOrderCreateRequest(
        @NotNull(message = "客户不能为空") Long customerId,
        @NotNull(message = "订单日期不能为空") LocalDate orderDate,
        @NotEmpty(message = "订单明细不能为空") List<@Valid SalesOrderItemRequest> items) {
}
