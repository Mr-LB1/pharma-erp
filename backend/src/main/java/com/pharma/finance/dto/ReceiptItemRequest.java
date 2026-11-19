package com.pharma.finance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** 收款核销明细请求。 */
public record ReceiptItemRequest(
        @NotNull(message = "应收单不能为空") Long arInvoiceId,
        @NotNull(message = "核销金额不能为空") @Positive(message = "核销金额必须大于0") BigDecimal amount) {
}
