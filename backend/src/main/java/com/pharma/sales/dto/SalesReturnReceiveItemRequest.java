package com.pharma.sales.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 销售退货验收明细：qualified=false 时登记不合格品。 */
public record SalesReturnReceiveItemRequest(
        @NotNull(message = "退货明细ID不能为空") Long itemId,
        Boolean qualified,
        @Size(max = 500, message = "不合格原因长度不能超过500") String rejectReason) {
}
