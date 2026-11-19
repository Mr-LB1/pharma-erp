package com.pharma.finance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 应付手工补录请求（bizType 固定 MANUAL，bizId 固定 0）。 */
public record ApInvoiceCreateRequest(
        @NotNull(message = "供应商不能为空") Long supplierId,
        @NotNull(message = "应付金额不能为空") @Positive(message = "应付金额必须大于0") BigDecimal amount,
        @Size(max = 500, message = "备注长度不能超过500") String remark) {
}
