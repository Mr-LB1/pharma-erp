package com.pharma.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 销售退货验收请求：逐明细给合格/不合格结论，未给出的明细按合格处理。 */
public record SalesReturnReceiveRequest(
        @NotEmpty(message = "验收明细不能为空") List<@Valid SalesReturnReceiveItemRequest> items) {
}
