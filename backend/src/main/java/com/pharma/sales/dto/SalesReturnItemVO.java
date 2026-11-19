package com.pharma.sales.dto;

import java.math.BigDecimal;

/** 销售退货明细视图。 */
public record SalesReturnItemVO(Long id,
                                Long productId,
                                String productName,
                                String batchNo,
                                BigDecimal quantity,
                                BigDecimal unitPrice,
                                BigDecimal unitCost,
                                Long warehouseId,
                                String acceptResult,
                                String rejectReason) {
}
