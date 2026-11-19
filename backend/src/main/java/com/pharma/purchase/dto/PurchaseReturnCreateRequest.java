package com.pharma.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 采购退货创建请求：创建即扣库存并生成应付红冲。 */
public record PurchaseReturnCreateRequest(
        Long receiveId,
        Long supplierId,
        @NotNull(message = "退货日期不能为空") LocalDate returnDate,
        @Size(max = 500, message = "退货原因长度不能超过500") String reason,
        @NotEmpty(message = "退货明细不能为空") List<@Valid PurchaseReturnItemRequest> items) {
}
