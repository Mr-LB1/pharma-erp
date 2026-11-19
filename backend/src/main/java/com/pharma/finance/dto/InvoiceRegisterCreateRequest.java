package com.pharma.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 发票登记请求。 */
public record InvoiceRegisterCreateRequest(
        @NotBlank(message = "发票号不能为空") @Size(max = 100, message = "发票号长度不能超过100") String invoiceNo,
        @Size(max = 30, message = "关联单据类型长度不能超过30") String bizType,
        Long bizId,
        @NotNull(message = "发票金额不能为空") @Positive(message = "发票金额必须大于0") BigDecimal amount,
        @Size(max = 500, message = "备注长度不能超过500") String remark) {
}
