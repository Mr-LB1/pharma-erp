package com.pharma.purchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseReceiveItemRequest(
        @NotNull(message = "商品不能为空") Long productId,
        @NotNull(message = "数量不能为空") @Positive(message = "数量必须大于0") BigDecimal quantity,
        @NotBlank(message = "批号不能为空") @Size(max = 50, message = "批号长度不能超过50") String batchNo,
        LocalDate produceDate,
        @NotNull(message = "效期不能为空") LocalDate expireDate,
        @NotNull(message = "入库单价不能为空") @DecimalMin(value = "0", message = "入库单价不能为负") BigDecimal unitCost,
        @Size(max = 100, message = "检验单号长度不能超过100") String inspectionNo) {
}
