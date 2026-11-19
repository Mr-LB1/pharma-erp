package com.pharma.purchase.dto;

import java.math.BigDecimal;

/** 采购退货明细视图。 */
public record PurchaseReturnItemVO(Long id,
                                   Long productId,
                                   String productName,
                                   String batchNo,
                                   BigDecimal quantity,
                                   BigDecimal unitCost,
                                   Long warehouseId,
                                   BigDecimal actualQty,
                                   BigDecimal qtyDiff) {
}
