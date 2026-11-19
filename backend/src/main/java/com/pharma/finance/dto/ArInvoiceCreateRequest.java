package com.pharma.finance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 应收手工补录请求（bizType 固定 MANUAL，bizId 固定 0）。 */
public record ArInvoiceCreateRequest(
        @NotNull(message = "客户不能为空") Long customerId,
        @NotNull(message = "应收金额不能为空") @Positive(message = "应收金额必须大于0") BigDecimal amount,
        @Size(max = 500, message = "备注长度不能超过500") String remark) {
}
