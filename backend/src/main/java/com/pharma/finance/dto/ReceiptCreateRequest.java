package com.pharma.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 收款单创建请求：amount 缺省时按核销明细合计。 */
public record ReceiptCreateRequest(
        @NotNull(message = "收款客户不能为空") Long customerId,
        BigDecimal amount,
        @Size(max = 500, message = "备注长度不能超过500") String remark,
        @NotEmpty(message = "收款核销明细不能为空") List<@Valid ReceiptItemRequest> items) {
}
