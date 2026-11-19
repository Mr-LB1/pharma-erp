package com.pharma.inventory.dto;

import java.math.BigDecimal;

/** 调拨明细视图。 */
public record StockTransferItemVO(Long id,
                                  Long productId,
                                  String productName,
                                  String batchNo,
                                  BigDecimal quantity,
                                  BigDecimal actualQty,
                                  BigDecimal qtyDiff) {
}
